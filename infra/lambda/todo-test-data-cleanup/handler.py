import json
import logging
import os
from datetime import datetime, timezone
from typing import Any

import boto3

# なぜ必要か: 仕様で許可された userPrefix 以外を受け付けないため。
ALLOWED_USER_PREFIXES = {"loadtest_", "*"}
# なぜ必要か: 大量削除時のロック時間を抑える既定バッチサイズを統一するため。
DEFAULT_BATCH_SIZE = 500

LOGGER = logging.getLogger()
LOGGER.setLevel(logging.INFO)


def _require_env(name: str) -> str:
    # なぜ必要か: 実行時に必須設定が欠落したまま誤動作することを防ぐため。
    value = os.getenv(name)
    if not value:
        raise ValueError(f"必須環境変数が未設定です: {name}")
    return value


def _parse_user_prefix(event: Any) -> str:
    # なぜ必要か: Lambda Test Event の入力形式を固定し、運用時の入力ミスを即時検知するため。
    if not isinstance(event, dict):
        raise ValueError("実行イベントはJSONオブジェクト形式で指定してください。")

    # なぜ必要か: 全件削除を含む強い操作を、仕様で許可した値に限定するため。
    user_prefix = event.get("userPrefix")
    if user_prefix not in ALLOWED_USER_PREFIXES:
        raise ValueError("userPrefix は 'loadtest_' または '*' のみ指定できます。")

    return str(user_prefix)


def _parse_batch_size() -> int:
    # なぜ必要か: 環境変数で削除粒度を調整できるようにしつつ、不正値を排除するため。
    raw_value = os.getenv("BATCH_SIZE", str(DEFAULT_BATCH_SIZE))
    try:
        batch_size = int(raw_value)
    except ValueError as exc:
        raise ValueError("BATCH_SIZE は整数で指定してください。") from exc

    if batch_size <= 0:
        raise ValueError("BATCH_SIZE は 1 以上を指定してください。")

    return batch_size


def _execute_statement(
    client: Any,
    *,
    resource_arn: str,
    secret_arn: str,
    database_name: str,
    sql: str,
    parameters: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    # なぜ必要か: Data API 呼び出しを共通化し、SQL実行時のパラメータ組み立てを一貫化するため。
    request: dict[str, Any] = {
        "resourceArn": resource_arn,
        "secretArn": secret_arn,
        "database": database_name,
        "sql": sql,
    }
    if parameters:
        request["parameters"] = parameters
    return client.execute_statement(**request)


def _extract_count(response: dict[str, Any]) -> int:
    # なぜ必要か: Data API の戻り値形式差異を吸収して件数を確実に数値化するため。
    records = response.get("records", [])
    if not records or not records[0]:
        return 0

    value = records[0][0]
    if "longValue" in value:
        return int(value["longValue"])
    if "stringValue" in value:
        return int(value["stringValue"])
    if "doubleValue" in value:
        return int(value["doubleValue"])
    return 0


def _build_count_query(user_prefix: str) -> tuple[str, list[dict[str, Any]]]:
    # なぜ必要か: 監査ログ用の対象件数を、全件/プレフィックス条件ごとに正しく取得するため。
    if user_prefix == "*":
        return "SELECT COUNT(*) FROM todos;", []

    return (
        "SELECT COUNT(*) FROM todos WHERE owner_subject LIKE :owner_prefix;",
        [
            {
                "name": "owner_prefix",
                "value": {"stringValue": "loadtest_%"},
            }
        ],
    )


def _build_delete_query(user_prefix: str, batch_size: int) -> tuple[str, list[dict[str, Any]]]:
    # なぜ必要か: 1回の削除件数を制御し、大量削除時の負荷スパイクを緩和するため。
    common_parameters = [
        {
            "name": "batch_size",
            "value": {"longValue": batch_size},
        }
    ]

    if user_prefix == "*":
        # なぜ必要か: 全件削除でも分割実行を維持し、長時間トランザクションを避けるため。
        return (
            """
WITH target_rows AS (
  SELECT id
  FROM todos
  ORDER BY id
  LIMIT CAST(:batch_size AS INTEGER)
)
DELETE FROM todos
WHERE id IN (SELECT id FROM target_rows);
""",
            common_parameters,
        )

    return (
        """
WITH target_rows AS (
  SELECT id
  FROM todos
  WHERE owner_subject LIKE :owner_prefix
  ORDER BY id
  LIMIT CAST(:batch_size AS INTEGER)
)
DELETE FROM todos
WHERE id IN (SELECT id FROM target_rows);
""",
        [
            {
                "name": "owner_prefix",
                "value": {"stringValue": "loadtest_%"},
            },
            *common_parameters,
        ],
    )


def lambda_handler(event: Any, context: Any) -> dict[str, Any]:
    # なぜ必要か: 実行時間帯を失敗時ログでも追跡できるよう、先に開始時刻を確定するため。
    start_time = datetime.now(timezone.utc).isoformat()

    try:
        # なぜ必要か: 入口で入力値・実行設定を厳格に検証し、誤実行を早期に停止するため。
        user_prefix = _parse_user_prefix(event)
        batch_size = _parse_batch_size()
        resource_arn = _require_env("DB_CLUSTER_ARN")
        secret_arn = _require_env("DB_SECRET_ARN")
        database_name = _require_env("DB_NAME")

        LOGGER.info(
            json.dumps(
                {
                    "message": "Todo cleanup started",
                    "requestId": getattr(context, "aws_request_id", None),
                    "userPrefix": user_prefix,
                    "batchSize": batch_size,
                    "startedAt": start_time,
                },
                ensure_ascii=False,
            )
        )

        data_api_client = boto3.client("rds-data")

        # なぜ必要か: 削除前に対象件数を把握し、実行妥当性をログで検証できるようにするため。
        count_sql, count_parameters = _build_count_query(user_prefix)
        count_response = _execute_statement(
            data_api_client,
            resource_arn=resource_arn,
            secret_arn=secret_arn,
            database_name=database_name,
            sql=count_sql,
            parameters=count_parameters,
        )
        target_count = _extract_count(count_response)

        # なぜ必要か: 0件になるまで小分けで削除し、タイムアウトとロック競合を抑えるため。
        deleted_count = 0
        while True:
            delete_sql, delete_parameters = _build_delete_query(user_prefix, batch_size)
            delete_response = _execute_statement(
                data_api_client,
                resource_arn=resource_arn,
                secret_arn=secret_arn,
                database_name=database_name,
                sql=delete_sql,
                parameters=delete_parameters,
            )

            deleted_in_batch = int(delete_response.get("numberOfRecordsUpdated", 0))
            if deleted_in_batch == 0:
                break

            deleted_count += deleted_in_batch
            LOGGER.info(
                json.dumps(
                    {
                        "message": "Todo cleanup batch deleted",
                        "userPrefix": user_prefix,
                        "deletedInBatch": deleted_in_batch,
                        "deletedTotal": deleted_count,
                    },
                    ensure_ascii=False,
                )
            )

        # なぜ必要か: 終了サマリを構造化ログで残し、運用者が結果確認しやすくするため。
        finished_at = datetime.now(timezone.utc).isoformat()
        LOGGER.info(
            json.dumps(
                {
                    "message": "Todo cleanup finished",
                    "userPrefix": user_prefix,
                    "targetCount": target_count,
                    "deletedCount": deleted_count,
                    "startedAt": start_time,
                    "finishedAt": finished_at,
                    "status": "SUCCEEDED",
                },
                ensure_ascii=False,
            )
        )

        return {
            "status": "SUCCEEDED",
            "userPrefix": user_prefix,
            "targetCount": target_count,
            "deletedCount": deleted_count,
            "startedAt": start_time,
            "finishedAt": finished_at,
        }
    except Exception:
        # なぜ必要か: 失敗時にも時刻と状態を残し、CloudWatch Logs から原因追跡しやすくするため。
        finished_at = datetime.now(timezone.utc).isoformat()
        LOGGER.exception(
            json.dumps(
                {
                    "message": "Todo cleanup failed",
                    "startedAt": start_time,
                    "finishedAt": finished_at,
                    "status": "FAILED",
                },
                ensure_ascii=False,
            )
        )
        raise
