#!/usr/bin/env python3
"""Create or align load test users in Cognito User Pool.

This script is idempotent:
- Existing users are reused.
- Permanent password is always re-applied.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
from pathlib import Path
from typing import Callable, Iterable

# なぜ必要か: Cognito API は一時的なスロットリングが発生しうるため、再試行対象を明示して可用性を上げるため。
RETRYABLE_ERROR_CODES = {
    "TooManyRequestsException",
    "ThrottlingException",
    "LimitExceededException",
    "InternalErrorException",
    "ServiceUnavailableException",
}

USER_POOL_OUTPUT_KEY = "TodoAppCognitoUserPoolId"


def parse_args() -> argparse.Namespace:
    # なぜ必要か: 実行環境差分を吸収し、同一スクリプトを prod 固定方針のまま再利用できるようにするため。
    parser = argparse.ArgumentParser(description="Create load test users in Cognito User Pool")
    parser.add_argument("--region", default="ap-northeast-1")
    parser.add_argument("--stack-name", default="InfraStack-prod")
    parser.add_argument("--user-count", type=int, default=100)
    parser.add_argument("--user-prefix", default="loadtest")
    parser.add_argument("--user-domain", default="test.local")
    parser.add_argument(
        "--password",
        default=os.environ.get("LOAD_TEST_USER_PASSWORD"),
        help="Permanent password for all generated users. "
        "You can set LOAD_TEST_USER_PASSWORD env instead.",
    )
    parser.add_argument("--sleep-seconds", type=float, default=0.01)
    parser.add_argument("--max-retries", type=int, default=5)
    parser.add_argument("--base-backoff-seconds", type=float, default=0.2)
    parser.add_argument(
        "--manifest-path",
        default=None,
        help="Optional output JSON path for created/updated user list.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=False,
        help="Do not call AWS APIs. Generate summary only.",
    )
    return parser.parse_args()


def fail(message: str) -> None:
    # なぜ必要か: 失敗時に即時終了し、後続処理で部分的に不整合な状態を作らないため。
    print(f"[ERROR] {message}", file=sys.stderr)
    raise SystemExit(1)


def get_error_code(error: Exception) -> str:
    # なぜ必要か: boto3例外からエラーコードを抽出し、再試行可否を機械的に判定するため。
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
    # なぜ必要か: 一時的なAWS API失敗を吸収し、100ユーザー一括処理の完走率を高めるため。
    for attempt in range(max_retries + 1):
        try:
            return func()
        except Exception as error:  # noqa: PERF203
            error_code = get_error_code(error)
            is_retryable = error_code in RETRYABLE_ERROR_CODES
            if not is_retryable or attempt >= max_retries:
                raise
            # なぜ必要か: 指数バックオフ+jitterで同時再試行衝突を回避し、再スロットリングを減らすため。
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
    # なぜ必要か: 実環境ごとのUser Pool IDハードコードを避け、CloudFormationを唯一の参照元にするため。
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
    # なぜ必要か: 再実行時も同一命名規則でユーザーを再計算し、冪等な整列を可能にするため。
    for number in range(1, count + 1):
        yield f"{prefix}-{number:04d}@{domain}"


def user_exists(cognito_idp_client, user_pool_id: str, username: str) -> bool:
    # なぜ必要か: 既存ユーザーを検知して再作成を避け、不要な失敗と副作用を抑えるため。
    try:
        cognito_idp_client.admin_get_user(UserPoolId=user_pool_id, Username=username)
        return True
    except cognito_idp_client.exceptions.UserNotFoundException:
        return False


def ensure_user(
    cognito_idp_client,
    user_pool_id: str,
    username: str,
    password: str,
    max_retries: int,
    base_backoff_seconds: float,
) -> str:
    # なぜ必要か: 作成有無にかかわらず最終的に恒久パスワード状態へ揃え、JWT生成前提を満たすため。
    created = False
    if not user_exists(cognito_idp_client, user_pool_id, username):
        # なぜ必要か: 招待メール送信を抑止し、負荷試験用ユーザーを静かに準備するため。
        call_with_retry(
            action_name="admin_create_user",
            func=lambda: cognito_idp_client.admin_create_user(
                UserPoolId=user_pool_id,
                Username=username,
                UserAttributes=[
                    {"Name": "email", "Value": username},
                    {"Name": "email_verified", "Value": "true"},
                ],
                MessageAction="SUPPRESS",
                TemporaryPassword=password,
            ),
            max_retries=max_retries,
            base_backoff_seconds=base_backoff_seconds,
        )
        created = True

    call_with_retry(
        action_name="admin_set_user_password",
        func=lambda: cognito_idp_client.admin_set_user_password(
            UserPoolId=user_pool_id,
            Username=username,
            Password=password,
            Permanent=True,
        ),
        max_retries=max_retries,
        base_backoff_seconds=base_backoff_seconds,
    )

    return "created" if created else "updated"


def write_manifest(path_str: str, payload: dict) -> None:
    # なぜ必要か: 実行結果をJSONで残し、監査や失敗時の差分確認を容易にするため。
    path = Path(path_str)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    # なぜ必要か: 全処理を一箇所に集約し、CLIエントリポイントの見通しを保つため。
    args = parse_args()

    if args.user_count <= 0:
        fail("--user-count must be > 0")
    if args.dry_run:
        # なぜ必要か: 本番影響なしで件数・命名規則・出力形式だけを先に検証できるようにするため。
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
            "createdCount": args.user_count,
            "updatedCount": 0,
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

    if not args.password:
        fail("--password or LOAD_TEST_USER_PASSWORD is required")

    try:
        # なぜ必要か: dry-run時はboto3未導入でも動かし、本実行時のみ依存を要求するため。
        import boto3  # pylint: disable=import-outside-toplevel
    except ModuleNotFoundError:
        fail("boto3 is required. Run: python3.13 -m pip install -r requirements.txt")

    cloudformation_client = boto3.client("cloudformation", region_name=args.region)
    cognito_idp_client = boto3.client("cognito-idp", region_name=args.region)

    user_pool_id = resolve_user_pool_id(cloudformation_client, args.stack_name)
    print(f"[INFO] target stack={args.stack_name} region={args.region}")
    print(f"[INFO] target user_pool_id={user_pool_id}")
    print(f"[INFO] target users={args.user_count}")

    created_count = 0
    updated_count = 0
    users = []
    started_at = int(time.time())

    for username in iter_usernames(args.user_prefix, args.user_domain, args.user_count):
        # なぜ必要か: 1ユーザー単位で結果を確定し、途中失敗時も進捗を失わないようにするため。
        result = ensure_user(
            cognito_idp_client=cognito_idp_client,
            user_pool_id=user_pool_id,
            username=username,
            password=args.password,
            max_retries=args.max_retries,
            base_backoff_seconds=args.base_backoff_seconds,
        )
        users.append({"username": username, "result": result})
        if result == "created":
            created_count += 1
        else:
            updated_count += 1
        # なぜ必要か: API連打を避け、スロットリング発生率を下げるため。
        time.sleep(args.sleep_seconds)

    ended_at = int(time.time())
    summary = {
        "region": args.region,
        "stackName": args.stack_name,
        "userPoolId": user_pool_id,
        "userPrefix": args.user_prefix,
        "userDomain": args.user_domain,
        "userCount": args.user_count,
        "createdCount": created_count,
        "updatedCount": updated_count,
        "startedAtEpochSeconds": started_at,
        "endedAtEpochSeconds": ended_at,
        "users": users,
    }

    print(
        "[INFO] user alignment completed "
        f"(created={created_count}, updated={updated_count}, total={args.user_count})"
    )

    if args.manifest_path:
        write_manifest(args.manifest_path, summary)
        print(f"[INFO] manifest written: {args.manifest_path}")


if __name__ == "__main__":
    main()
