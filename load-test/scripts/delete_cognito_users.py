#!/usr/bin/env python3
"""Delete load test users from Cognito User Pool."""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
from pathlib import Path
from typing import Callable, Iterable

# なぜ必要か: Cognito API は一時的なスロットリングが発生しうるため、再試行対象を明示して完走率を上げるため。
RETRYABLE_ERROR_CODES = {
    "TooManyRequestsException",
    "ThrottlingException",
    "LimitExceededException",
    "InternalErrorException",
    "ServiceUnavailableException",
}

USER_POOL_OUTPUT_KEY = "TodoAppCognitoUserPoolId"


def parse_args() -> argparse.Namespace:
    # なぜ必要か: 実行環境差分を引数化し、同一スクリプトで再利用できるようにするため。
    parser = argparse.ArgumentParser(description="Delete load test users from Cognito User Pool")
    parser.add_argument("--region", default="ap-northeast-1")
    parser.add_argument("--stack-name", default="InfraStack-prod")
    parser.add_argument("--user-count", type=int, default=100)
    parser.add_argument("--user-prefix", default="loadtest")
    parser.add_argument("--user-domain", default="test.local")
    parser.add_argument("--sleep-seconds", type=float, default=0.01)
    parser.add_argument("--max-retries", type=int, default=5)
    parser.add_argument("--base-backoff-seconds", type=float, default=0.2)
    parser.add_argument(
        "--on-not-found",
        choices=("skip", "fail"),
        default="skip",
        help="Behavior when user does not exist.",
    )
    parser.add_argument(
        "--manifest-path",
        default=None,
        help="Optional output JSON path for delete summary.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=False,
        help="Do not call AWS APIs. Generate summary only.",
    )
    return parser.parse_args()


def fail(message: str) -> None:
    # なぜ必要か: 失敗時に即時終了し、削除結果の取り違えを防ぐため。
    print(f"[ERROR] {message}", file=sys.stderr)
    raise SystemExit(1)


def get_error_code(error: Exception) -> str:
    # なぜ必要か: boto3例外からエラーコードを抽出し、再試行可否を機械判定するため。
    response = getattr(error, "response", None)
    if isinstance(response, dict):
        inner = response.get("Error", {})
        if isinstance(inner, dict):
            code = inner.get("Code")
            if isinstance(code, str):
                return code
    return "Unknown"


def call_with_retry(
    action_name: str,
    func: Callable[[], dict],
    max_retries: int,
    base_backoff_seconds: float,
) -> dict:
    # なぜ必要か: 一時的なAWS API失敗を吸収し、全ユーザー削除の完了性を高めるため。
    for attempt in range(max_retries + 1):
        try:
            return func()
        except Exception as error:  # noqa: PERF203
            error_code = get_error_code(error)
            is_retryable = error_code in RETRYABLE_ERROR_CODES
            if not is_retryable or attempt >= max_retries:
                raise
            # なぜ必要か: 指数バックオフ+jitterで再試行集中を避け、再スロットリングを抑えるため。
            backoff = (2**attempt) * base_backoff_seconds
            jitter = random.uniform(0, base_backoff_seconds)
            sleep_seconds = backoff + jitter
            print(
                f"[WARN] {action_name} failed with {error_code}. "
                f"retry={attempt + 1}/{max_retries}, sleep={sleep_seconds:.2f}s"
            )
            time.sleep(sleep_seconds)
    raise RuntimeError(f"{action_name} failed unexpectedly")


def resolve_user_pool_id(cloudformation_client, stack_name: str) -> str:
    # なぜ必要か: User Pool ID の手入力を避け、対象環境をCloudFormation出力に固定するため。
    response = cloudformation_client.describe_stacks(StackName=stack_name)
    stacks = response.get("Stacks", [])
    if not stacks:
        fail(f"CloudFormation stack not found: {stack_name}")

    outputs = stacks[0].get("Outputs", [])
    for output in outputs:
        if output.get("OutputKey") == USER_POOL_OUTPUT_KEY:
            value = output.get("OutputValue")
            if value:
                return value

    fail(f"CloudFormation output not found: {USER_POOL_OUTPUT_KEY}")
    raise AssertionError("unreachable")


def iter_usernames(prefix: str, domain: str, count: int) -> Iterable[str]:
    # なぜ必要か: 作成時と同じ命名規則で対象ユーザー集合を再現するため。
    for number in range(1, count + 1):
        yield f"{prefix}-{number:04d}@{domain}"


def delete_user(
    cognito_idp_client,
    user_pool_id: str,
    username: str,
    max_retries: int,
    base_backoff_seconds: float,
) -> str:
    # なぜ必要か: 1ユーザー単位で削除し、結果を deleted/not_found に分類して可観測性を上げるため。
    try:
        call_with_retry(
            action_name="admin_delete_user",
            func=lambda: cognito_idp_client.admin_delete_user(
                UserPoolId=user_pool_id,
                Username=username,
            ),
            max_retries=max_retries,
            base_backoff_seconds=base_backoff_seconds,
        )
        return "deleted"
    except cognito_idp_client.exceptions.UserNotFoundException:
        return "not_found"


def write_manifest(path_str: str, payload: dict) -> None:
    # なぜ必要か: 実行結果をJSONで残し、削除漏れ・再実行判断をしやすくするため。
    path = Path(path_str)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    # なぜ必要か: 引数解析から削除実行までの制御を一箇所にまとめ、運用手順を単純化するため。
    args = parse_args()

    if args.user_count <= 0:
        fail("--user-count must be > 0")

    if args.dry_run:
        # なぜ必要か: 本番影響なしで対象ユーザー集合と出力形式を先に確認できるようにするため。
        started_at = int(time.time())
        users = [
            {"username": username, "result": "planned"}
            for username in iter_usernames(args.user_prefix, args.user_domain, args.user_count)
        ]
        ended_at = int(time.time())
        summary = {
            "region": args.region,
            "stackName": args.stack_name,
            "userPoolId": "dry-run",
            "userPrefix": args.user_prefix,
            "userDomain": args.user_domain,
            "userCount": args.user_count,
            "deletedCount": args.user_count,
            "notFoundCount": 0,
            "startedAtEpochSeconds": started_at,
            "endedAtEpochSeconds": ended_at,
            "users": users,
            "dryRun": True,
        }
        print(f"[INFO] dry-run mode users={args.user_count}")
        if args.manifest_path:
            write_manifest(args.manifest_path, summary)
            print(f"[INFO] manifest written: {args.manifest_path}")
        return

    try:
        # なぜ必要か: dry-runでは依存を要求せず、本実行時のみboto3を必須化するため。
        import boto3  # pylint: disable=import-outside-toplevel
    except ModuleNotFoundError:
        fail("boto3 is required. Run: python3.13 -m pip install -r requirements.txt")

    cloudformation_client = boto3.client("cloudformation", region_name=args.region)
    cognito_idp_client = boto3.client("cognito-idp", region_name=args.region)

    user_pool_id = resolve_user_pool_id(cloudformation_client, args.stack_name)
    print(f"[INFO] target stack={args.stack_name} region={args.region}")
    print(f"[INFO] target user_pool_id={user_pool_id}")
    print(f"[INFO] target users={args.user_count}")

    deleted_count = 0
    not_found_count = 0
    users = []
    started_at = int(time.time())

    for username in iter_usernames(args.user_prefix, args.user_domain, args.user_count):
        # なぜ必要か: ユーザー単位で進捗を確定し、途中失敗時も状況把握できるようにするため。
        result = delete_user(
            cognito_idp_client=cognito_idp_client,
            user_pool_id=user_pool_id,
            username=username,
            max_retries=args.max_retries,
            base_backoff_seconds=args.base_backoff_seconds,
        )
        users.append({"username": username, "result": result})
        if result == "deleted":
            deleted_count += 1
        elif result == "not_found":
            not_found_count += 1
            if args.on_not_found == "fail":
                fail(f"user not found: {username}")

        # なぜ必要か: API連打を避け、削除処理のスロットリングを緩和するため。
        time.sleep(args.sleep_seconds)

    ended_at = int(time.time())
    summary = {
        "region": args.region,
        "stackName": args.stack_name,
        "userPoolId": user_pool_id,
        "userPrefix": args.user_prefix,
        "userDomain": args.user_domain,
        "userCount": args.user_count,
        "deletedCount": deleted_count,
        "notFoundCount": not_found_count,
        "startedAtEpochSeconds": started_at,
        "endedAtEpochSeconds": ended_at,
        "users": users,
    }

    print(
        "[INFO] user deletion completed "
        f"(deleted={deleted_count}, notFound={not_found_count}, total={args.user_count})"
    )

    if args.manifest_path:
        write_manifest(args.manifest_path, summary)
        print(f"[INFO] manifest written: {args.manifest_path}")


if __name__ == "__main__":
    main()
