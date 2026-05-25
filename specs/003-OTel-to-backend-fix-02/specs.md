# Spec: OTel-to-backend fix-02（Datadog タグ戦略・ログ相関修正）

## 概要
- `specs/003-OTel-to-backend/` で実装済みの backend オブザーバビリティを、fix-02 の ADR に合わせて改修するための正式要件を定義する。
- 主眼は、Datadog における Logs ↔ Traces 相関の安定化と、`service` / `env` / `version` を中心にしたタグ整合の担保である。

## 背景
- 既存 backend には `X-Amzn-Trace-Id` 由来の `traceId` と OpenTelemetry `trace_id` が混在し得る構造があり、Datadog APM 相関の誤認・実装ぶれリスクがある。
- 同一 Datadog Organization で複数 AWS アカウント/システムを運用する前提では、Unified Service Tagging と補助タグの責務分離が必要である。
- infra 側で OTLP/gRPC + Datadog Agent sidecar を前提とする構成が確定したため、backend 側もその契約に明示的に整合させる必要がある。

## 目的
- backend ログのトップレベルで `trace_id` / `span_id` を一貫して出力し、Datadog 上で双方向相関できる状態を確立する。
- backend 側テレメトリ属性と infra 側 Datadog タグ注入（`DD_SERVICE` / `DD_ENV` / `DD_VERSION` / `DD_TAGS`）の不整合を防ぐ。
- 既存責務分離（Controller / Service / Repository）を維持したまま、観測性に関わる修正のみを最小範囲で実施可能な要件に整理する。

## スコープ
- 対象領域は `backend/` の Spring Boot 実装（設定、依存関係、ログ相関、初期化、関連テスト）とする。
- OpenTelemetry の相関キー方針を `trace_id` / `span_id` に統一し、`Span.current().getSpanContext()` を正とする。
- `opentelemetry-logback-appender-1.0` の導入、および Spring 起動時の `OpenTelemetryAppender.install(openTelemetry)` 初期化要件を定義する。
- backend 側が前提とする infra 契約（`DD_VERSION` の単一ソース等）を仕様として明文化する。

## 対象外
- `infra/` の ECS タスク定義、Datadog Agent/FireLens コンテナ定義、CloudWatch Logs 保持期間の実装変更。
- OpenTelemetry Logs API への業務ログ全面移行。
- Datadog ダッシュボード、Monitor、Index/Exclusion Filter の詳細設計・実装。
- 認証・認可仕様や業務 API 契約の変更。

## ユーザーストーリー / 利用シナリオ
- 運用担当者として、Datadog Logs で障害ログを見つけたときに、同一 `trace_id` から APM Trace へ遷移し、根本原因を短時間で特定したい。
- 開発者として、トレース相関キーが `trace_id` / `span_id` に統一されることで、レビュー時に「どのキーを採用すべきか」を迷わず実装したい。
- SRE として、`service` / `env` / `version` が logs/traces/metrics で一致する前提のもと、環境差分やデプロイ影響を横断分析したい。

## 機能要件
- REQ-SPEC-FIX02-001 相関キー統一
  - ログ相関の主キーは `trace_id` / `span_id` とする。
  - 値は `Span.current().getSpanContext()` 由来を正とし、独自生成・上書きを禁止する。
  - `trace_id` は 32 文字小文字 hex、`span_id` は 16 文字小文字 hex を満たす。

- REQ-SPEC-FIX02-002 補助トレース情報の扱い
  - `X-Amzn-Trace-Id` は主キーとして扱わず、必要時のみ補助キー（`x_amzn_trace_id`）として扱う。
  - `traceId` のような曖昧な新規キー名は採用しない。

- REQ-SPEC-FIX02-003 Logback Appender 初期化
  - `opentelemetry-logback-appender-1.0` を backend 依存に追加する。
  - Spring 起動時に `OpenTelemetryAppender.install(openTelemetry)` を実行する初期化処理を持つ。
  - 初期化失敗時は原因追跡が可能なログを残し、障害検知可能性を担保する。

- REQ-SPEC-FIX02-004 Unified Service Tagging 契約
  - backend は `OTEL_SERVICE_NAME` と `OTEL_RESOURCE_ATTRIBUTES`（`deployment.environment`、`service.version`）を設定できる前提で動作する。
  - infra 側の `DD_SERVICE` / `DD_ENV` / `DD_VERSION` と矛盾しない値を使用する。
  - `DD_VERSION` および `service.version` の単一ソースは `infra/lib/constructs/backend-image-deployment-construct.ts` の `backendDockerImageAsset.imageTag` とする。

- REQ-SPEC-FIX02-005 補助タグ契約
  - backend は infra 側で付与される `DD_TAGS=team:<team> aws_account:<aws-account-id> system:<system-name>` を前提に観測可能性を検証する。
  - `service` / `env` / `version` を主キー、`team` / `aws_account` / `system` を補助タグとして扱う運用を阻害しない。

## 非機能要件
- 保守性
  - 変更は観測性レイヤ（ログ相関・設定・初期化）に限定し、業務ロジックへの侵襲を最小化する。
  - 既存レイヤ責務（Controller / Service / Repository）を維持する。

- セキュリティ
  - トークン本文、PII、シークレットをログへ出力しない既存方針を維持する。
  - 相関キーは識別子として扱い、機微情報を含めない。

- 運用性
  - Datadog 上で Trace ↔ Logs の双方向遷移が運用手順として再現可能であること。
  - ログキー名の揺れに起因する運用誤判定を防ぐこと。

- 互換性
  - 既存 API 契約・DB スキーマ・認証フローに影響を与えない。

## 受け入れ条件
- backend ログのトップレベルに `trace_id` / `span_id` が出力される。
- `trace_id` が 32 文字小文字 hex、`span_id` が 16 文字小文字 hex を満たす。
- `traceId` キーが新規仕様・新規実装で使用されていない。
- `x_amzn_trace_id` は補助情報としてのみ扱われ、主キー運用されない。
- Datadog 上で Logs から Trace、Trace から Logs の双方向遷移が確認できる。
- `service` / `env` / `version` が logs/traces で一致する。
- `team` / `aws_account` / `system` が補助タグとして付与される前提で検索・絞り込み可能である。

## 制約
- テレメトリ送信経路は OTLP/gRPC（`localhost:4317` 経由）前提とし、backend 側で OTLP/HTTP 主経路へ戻さない。
- 実装対象は backend 領域中心だが、タグ最終注入は infra 実装に依存するため、単独では完結しない。
- `DD_SERVICE` / `DD_ENV` / `DD_VERSION` を `DD_TAGS` に重複定義しない運用ルールを前提とする。

## 依存関係
- 仕様依存:
  - `specs/003-OTel-to-backend-fix-02/specs-draft.md`
  - `specs/004-Datadog-agent-to-cdk/specs-draft.md`
- ADR 依存:
  - `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`
  - `docs/adr/adr-0002-trace-correlation-otel-datadog.md`
- 実装依存:
  - `backend/pom.xml`（OTel Logback Appender 依存）
  - `infra/lib/constructs/backend-image-deployment-construct.ts`（`backendDockerImageAsset.imageTag`）
  - `infra/lib/config/environment-config.ts`（`team` / `service-name` / `system-name` 等の共通値管理）

## 未確定事項 / 要確認事項
- Datadog Site の最終値（`datadoghq.com` / `ap1.datadoghq.com` など）の環境適用方針。
- Datadog 側で `trace_id` を予約属性として扱うための Preprocessing/Remapper 設定の最終運用手順。
- Datadog monitor / dashboard / index 設計の責任分界（backend 側検証範囲と infra/運用側範囲）。
