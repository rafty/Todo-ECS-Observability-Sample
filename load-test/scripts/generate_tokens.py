#!/usr/bin/env python3
"""Generate JWT tokens for load test users with AdminInitiateAuth."""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
from pathlib import Path
from typing import Callable, Iterable

# なぜ必要か: CloudFormation出力キーを定数化し、参照先の揺れを防ぐため。
USER_POOL_OUTPUT_KEY = "TodoAppCognitoUserPoolId"
USER_POOL_CLIENT_ID_OUTPUT_KEY = "TodoAppCognitoUserPoolClientId"

# なぜ必要か: 一時障害時の再試行対象を限定し、恒久エラーでの無駄なリトライを避けるため。
RETRYABLE_ERROR_CODES = {
    "TooManyRequestsException",
    "ThrottlingException",
    "LimitExceededException",
    "InternalErrorException",
    "ServiceUnavailableException",
}


def parse_args() -> argparse.Namespace:
    # なぜ必要か: 運用時に件数・失敗方針・出力先を引数で切り替えられるようにするため。
    parser = argparse.ArgumentParser(description="Generate JWT tokens for load test users")
    parser.add_argument("--region", default="ap-northeast-1")
    parser.add_argument("--stack-name", default="InfraStack-prod")
    parser.add_argument("--user-count", type=int, default=100)
    parser.add_argument("--user-prefix", default="loadtest")
    parser.add_argument("--user-domain", default="test.local")
    parser.add_argument(
        "--password",
        default=os.environ.get("LOAD_TEST_USER_PASSWORD"),
        help="Permanent password for generated users. "
        "You can set LOAD_TEST_USER_PASSWORD env instead.",
    )
    parser.add_argument("--sleep-seconds", type=float, default=0.01)
    parser.add_argument("--max-retries", type=int, default=5)
    parser.add_argument("--base-backoff-seconds", type=float, default=0.2)
    parser.add_argument(
        "--on-failure",
        choices=("fail", "skip"),
        default="fail",
        help="Behavior when a single user's token cannot be generated after retries.",
    )
    parser.add_argument(
        "--output-path",
        default="k6/data/tokens.json",
        help="Output JSON path for token array.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=False,
        help="Do not call AWS APIs. Generate dummy token records only.",
    )
    return parser.parse_args()


def fail(message: str) -> None:
    # なぜ必要か: エラー検知時に即時終了し、欠損したtokens.jsonを後続処理で使わないため。
    print(f"[ERROR] {message}", file=sys.stderr)
    raise SystemExit(1)


def get_error_code(error: Exception) -> str:
    # なぜ必要か: boto3例外を共通形式へ正規化し、再試行判定ロジックを単純化するため。
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
    # なぜ必要か: `AdminInitiateAuth` の一時的失敗を吸収し、100件一括処理の成功率を上げるため。
    for attempt in range(max_retries + 1):
        try:
            return func()
        except Exception as error:  # noqa: PERF203
            error_code = get_error_code(error)
            is_retryable = error_code in RETRYABLE_ERROR_CODES
            if not is_retryable or attempt >= max_retries:
                raise
            # なぜ必要か: バックオフ+jitterで同時再試行集中を避け、再失敗の連鎖を減らすため。
            backoff = (2**attempt) * base_backoff_seconds
            jitter = random.uniform(0, base_backoff_seconds)
            sleep_seconds = backoff + jitter
            print(
                f"[WARN] {action_name} failed with {error_code}. "
                f"retry={attempt + 1}/{max_retries}, sleep={sleep_seconds:.2f}s"
            )
            time.sleep(sleep_seconds)
    raise RuntimeError(f"{action_name} failed unexpectedly")


def resolve_stack_outputs(cloudformation_client, stack_name: str) -> tuple[str, str]:
    # なぜ必要か: 対象環境のUser Pool/Clientを動的解決し、手入力ミスを防ぐため。
    response = cloudformation_client.describe_stacks(StackName=stack_name)
    stacks = response.get("Stacks", [])
    if not stacks:
        fail(f"CloudFormation stack not found: {stack_name}")

    outputs = stacks[0].get("Outputs", [])
    output_map = {output.get("OutputKey"): output.get("OutputValue") for output in outputs}

    user_pool_id = output_map.get(USER_POOL_OUTPUT_KEY)
    client_id = output_map.get(USER_POOL_CLIENT_ID_OUTPUT_KEY)
    if not user_pool_id:
        fail(f"CloudFormation output not found: {USER_POOL_OUTPUT_KEY}")
    if not client_id:
        fail(f"CloudFormation output not found: {USER_POOL_CLIENT_ID_OUTPUT_KEY}")

    return user_pool_id, client_id


def iter_usernames(prefix: str, domain: str, count: int) -> Iterable[str]:
    # なぜ必要か: ユーザー作成スクリプトと同じ規則で対象ユーザー集合を再計算するため。
    for number in range(1, count + 1):
        yield f"{prefix}-{number:04d}@{domain}"


def generate_token_for_user(
    cognito_idp_client,
    user_pool_id: str,
    client_id: str,
    username: str,
    password: str,
    max_retries: int,
    base_backoff_seconds: float,
) -> dict:
    # なぜ必要か: 管理者権限フローでJWTを取得し、DLTのBearer認証入力を機械生成するため。
    response = call_with_retry(
        action_name="admin_initiate_auth",
        func=lambda: cognito_idp_client.admin_initiate_auth(
            UserPoolId=user_pool_id,
            ClientId=client_id,
            AuthFlow="ADMIN_USER_PASSWORD_AUTH",
            AuthParameters={"USERNAME": username, "PASSWORD": password},
        ),
        max_retries=max_retries,
        base_backoff_seconds=base_backoff_seconds,
    )

    result = response.get("AuthenticationResult")
    if not result or "AccessToken" not in result:
        # なぜ必要か: 認証フロー未設定やユーザー状態異常を早期検知し、誤ったトークン配布を防ぐため。
        raise RuntimeError(
            f"AuthenticationResult is missing for user={username}. "
            "Check App Client auth flow and user password status."
        )

    issued_at = int(time.time())
    return {
        "username": username,
        "accessToken": result.get("AccessToken"),
        "idToken": result.get("IdToken"),
        "refreshToken": result.get("RefreshToken"),
        "tokenType": result.get("TokenType"),
        "expiresIn": result.get("ExpiresIn"),
        "issuedAtEpochSeconds": issued_at,
    }


def write_tokens(path_str: str, tokens: list[dict]) -> None:
    # なぜ必要か: K6が読み込めるJSON配列として保存し、後段処理との契約を固定するため。
    # 注意点: 出力には認証情報が含まれるため、実行後は必ず削除しコミットしないこと。
    path = Path(path_str)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(tokens, ensure_ascii=False, indent=2), encoding="utf-8")


def build_dry_run_tokens(prefix: str, domain: str, count: int) -> list[dict]:
    # なぜ必要か: AWS接続なしでフォーマット検証やZIP同梱確認を先に実施できるようにするため。
    issued_at = int(time.time())
    records = []
    for index, username in enumerate(iter_usernames(prefix, domain, count), start=1):
        records.append(
            {
                "username": username,
                "accessToken": f"dryrun-access-token-{index:04d}",
                "idToken": f"dryrun-id-token-{index:04d}",
                "refreshToken": f"dryrun-refresh-token-{index:04d}",
                "tokenType": "Bearer",
                "expiresIn": 3600,
                "issuedAtEpochSeconds": issued_at,
                "dryRun": True,
            }
        )
    return records


def main() -> None:
    # なぜ必要か: CLI引数処理から出力までの主制御を集約し、運用手順とコード対応を明確にするため。
    args = parse_args()

    if args.user_count <= 0:
        fail("--user-count must be > 0")
    if args.dry_run:
        # なぜ必要か: 本番環境へ影響を与えず、件数・出力形式だけ先行確認するため。
        tokens = build_dry_run_tokens(args.user_prefix, args.user_domain, args.user_count)
        write_tokens(args.output_path, tokens)
        print(f"[INFO] dry-run tokens generated: {len(tokens)}")
        print(f"[INFO] token file: {args.output_path}")
        return

    if not args.password:
        fail("--password or LOAD_TEST_USER_PASSWORD is required")

    try:
        # なぜ必要か: dry-runでは依存を要求せず、本実行時のみboto3を必須化するため。
        import boto3  # pylint: disable=import-outside-toplevel
    except ModuleNotFoundError:
        fail("boto3 is required. Run: python3.13 -m pip install -r requirements.txt")

    cloudformation_client = boto3.client("cloudformation", region_name=args.region)
    cognito_idp_client = boto3.client("cognito-idp", region_name=args.region)

    user_pool_id, client_id = resolve_stack_outputs(cloudformation_client, args.stack_name)
    print(f"[INFO] target stack={args.stack_name} region={args.region}")
    print(f"[INFO] target user_pool_id={user_pool_id}")
    print(f"[INFO] target client_id={client_id}")
    print(f"[INFO] target users={args.user_count}")

    tokens = []
    failures = []
    for username in iter_usernames(args.user_prefix, args.user_domain, args.user_count):
        # なぜ必要か: ユーザー単位で成功/失敗を分離し、`skip` 方針時に継続実行できるようにするため。
        try:
            token_record = generate_token_for_user(
                cognito_idp_client=cognito_idp_client,
                user_pool_id=user_pool_id,
                client_id=client_id,
                username=username,
                password=args.password,
                max_retries=args.max_retries,
                base_backoff_seconds=args.base_backoff_seconds,
            )
            tokens.append(token_record)
        except Exception as error:  # noqa: PERF203
            failures.append({"username": username, "error": str(error)})
            print(f"[WARN] token generation failed for user={username}: {error}")
            if args.on_failure == "fail":
                fail("Token generation aborted due to --on-failure=fail")
        # なぜ必要か: API連打を抑制し、認証APIのスロットリングを緩和するため。
        time.sleep(args.sleep_seconds)

    if args.on_failure == "fail" and failures:
        # なぜ必要か: fail方針時は不完全なtokens.jsonを成功扱いにしないため。
        fail("Token generation failed")

    write_tokens(args.output_path, tokens)
    print(f"[INFO] tokens generated: {len(tokens)}")
    print(f"[INFO] token file: {args.output_path}")

    if failures:
        # なぜ必要か: skip方針時に不足ユーザーを後続再実行で補完できるようにするため。
        print(f"[WARN] failed users: {len(failures)}")
        for failed in failures:
            print(f"[WARN] - {failed['username']}")


if __name__ == "__main__":
    main()
