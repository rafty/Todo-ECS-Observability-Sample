#!/usr/bin/env python3
"""Generate k6/data/config.json for Todo API K6 scenario."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from urllib.parse import urlparse


def parse_args() -> argparse.Namespace:
    # なぜ必要か: 実行環境差分を引数化し、同一スクリプトで再利用できるようにするため。
    parser = argparse.ArgumentParser(description="Create K6 config.json for DLT scenario")
    parser.add_argument(
        "--base-url",
        required=True,
        help="Todo API base URL. Example: https://dxxxxxxxxxxxxx.cloudfront.net",
    )
    parser.add_argument("--vus", type=int, default=100)
    parser.add_argument("--duration", default="10m")
    parser.add_argument("--sleep-seconds", type=float, default=0.2)
    parser.add_argument("--max-page-size", type=int, default=100)
    parser.add_argument("--output-path", default="k6/data/config.json")
    return parser.parse_args()


def fail(message: str) -> None:
    # なぜ必要か: 入力不備のまま不正な設定ファイルを出力しないため。
    print(f"[ERROR] {message}", file=sys.stderr)
    raise SystemExit(1)


def normalize_base_url(base_url: str) -> str:
    # なぜ必要か: URL形式を検証し、末尾スラッシュ差分による実行時不具合を防ぐため。
    normalized = base_url.strip().rstrip("/")
    parsed = urlparse(normalized)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        fail("--base-url must be a valid http(s) URL.")
    return normalized


def build_config(args: argparse.Namespace) -> dict:
    # なぜ必要か: k6シナリオが期待するキー形式へ変換し、入出力契約を固定するため。
    if args.vus <= 0:
        fail("--vus must be > 0")
    if not args.duration.strip():
        fail("--duration must not be empty")
    if args.sleep_seconds < 0:
        fail("--sleep-seconds must be >= 0")
    if args.max_page_size <= 0:
        fail("--max-page-size must be > 0")

    return {
        "baseUrl": normalize_base_url(args.base_url),
        "vus": args.vus,
        "duration": args.duration.strip(),
        "sleepSeconds": args.sleep_seconds,
        "maxPageSize": args.max_page_size,
    }


def write_config(path_str: str, config: dict) -> None:
    # なぜ必要か: DLT同梱対象パスへJSONを保存し、手作業ミスを防ぐため。
    path = Path(path_str)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"{json.dumps(config, ensure_ascii=False, indent=2)}\n", encoding="utf-8")


def main() -> None:
    # なぜ必要か: 引数解析・検証・保存を一連処理として実行するため。
    args = parse_args()
    config = build_config(args)
    write_config(args.output_path, config)
    print(f"[INFO] config file: {args.output_path}")
    print(f"[INFO] baseUrl: {config['baseUrl']}")
    print(f"[INFO] vus={config['vus']} duration={config['duration']}")


if __name__ == "__main__":
    main()
