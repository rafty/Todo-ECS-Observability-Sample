#!/usr/bin/env python3
"""Package K6 scenario artifacts for DLT upload."""

from __future__ import annotations

import argparse
import datetime as dt
import sys
import zipfile
from pathlib import Path


def parse_args() -> argparse.Namespace:
    # なぜ必要か: 実行場所や出力名を引数で切り替え、運用時の再利用性を高めるため。
    parser = argparse.ArgumentParser(description="Package load-test/k6 files into zip")
    parser.add_argument(
        "--k6-root",
        default="k6",
        help="Root directory that contains scenario scripts and data files.",
    )
    parser.add_argument(
        "--scenario-file",
        default="scenarios/todo_api_scenario.js",
        help="Scenario file path relative to --k6-root.",
    )
    parser.add_argument(
        "--output-dir",
        default="test-case",
        help="Output directory for zip artifact.",
    )
    parser.add_argument(
        "--output-name",
        default=None,
        help="Optional output zip file name. Default: dlt-k6-todo-scenario-<timestamp>.zip",
    )
    parser.add_argument(
        "--include-tokens",
        action="store_true",
        default=False,
        help="Include data/tokens.json when it exists. Default is false.",
    )
    return parser.parse_args()


def fail(message: str) -> None:
    # なぜ必要か: 入力不備時に即終了し、不完全なZIP作成を防ぐため。
    print(f"[ERROR] {message}", file=sys.stderr)
    raise SystemExit(1)


def is_included(path: Path, include_tokens: bool) -> bool:
    # なぜ必要か: DLT実行に不要な管理ファイルを除外し、提出物を最小構成に保つため。
    if path.name == ".gitkeep":
        return False
    if path.suffix == ".md":
        return False
    if not include_tokens and path.as_posix().endswith("data/tokens.json"):
        return False
    return True


def collect_files(k6_root: Path, include_tokens: bool) -> list[Path]:
    # なぜ必要か: ディレクトリ走査ロジックを分離し、対象ファイル判定を一元化するため。
    files = []
    for path in sorted(k6_root.rglob("*")):
        if not path.is_file():
            continue
        if is_included(path, include_tokens):
            files.append(path)
    return files


def ensure_required_files(k6_root: Path, scenario_file: Path, include_tokens: bool) -> None:
    # なぜ必要か: 実行に必須の資材不足をZIP化前に検知し、DLT実行失敗を未然に防ぐため。
    required_files = [
        scenario_file,
        k6_root / "data" / "config.json",
    ]
    # なぜ必要か: JWT同梱モード時のみトークン必須とし、用途に応じた検証にするため。
    if include_tokens:
        required_files.append(k6_root / "data" / "tokens.json")

    missing_files = [path for path in required_files if not path.exists()]
    if missing_files:
        missing = ", ".join(str(path) for path in missing_files)
        fail(f"required file is missing: {missing}")


def main() -> None:
    # なぜ必要か: 入力検証からZIP生成までの制御を集約し、手順の見通しを保つため。
    args = parse_args()
    k6_root = Path(args.k6_root).resolve()
    if not k6_root.exists():
        fail(f"k6 root does not exist: {k6_root}")

    scenario_file = (k6_root / args.scenario_file).resolve()
    if not scenario_file.exists():
        fail(f"scenario file does not exist: {scenario_file}")

    ensure_required_files(k6_root, scenario_file, include_tokens=args.include_tokens)
    files = collect_files(k6_root, include_tokens=args.include_tokens)
    if not files:
        fail(f"no package files found under {k6_root}")

    if args.output_name:
        output_name = args.output_name
    else:
        timestamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        output_name = f"dlt-k6-todo-scenario-{timestamp}.zip"

    output_dir = Path(args.output_dir).resolve()
    # なぜ必要か: 出力先ディレクトリを事前作成し、初回実行時の失敗を防ぐため。
    output_dir.mkdir(parents=True, exist_ok=True)
    zip_path = output_dir / output_name

    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for file_path in files:
            # なぜ必要か: ZIP内パスをk6ルート相対へ正規化し、DLT側の読み込み位置を固定するため。
            archive_name = file_path.relative_to(k6_root).as_posix()
            archive.write(file_path, archive_name)

    print(f"[INFO] zip file: {zip_path}")
    print(f"[INFO] included files: {len(files)}")
    for file_path in files:
        print(f"[INFO] - {file_path.relative_to(k6_root).as_posix()}")


if __name__ == "__main__":
    main()
