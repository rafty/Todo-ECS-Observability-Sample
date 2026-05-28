# Plan: Datadog Agent to CDK（ECS/Fargate オブザーバビリティ要件）

## 実装方針
- 本 feature は**複数領域（`infra/` 実装 + `docs/` 運用定義）**にまたがる。`app` / `datadog-agent` / `log_router` の 3 コンテナを ECS Fargate タスク定義で標準化し、ログとトレース/メトリクスの経路を分離する。
- ログは `awsfirelens`（Fluent Bit Datadog output）を主経路、traces/metrics は OTLP/gRPC（`localhost:4317`）を Datadog Agent sidecar 経由とし、`service` / `env` / `version` の軸を統一する。
- シークレットは Secrets Manager 経由でのみ注入し、タスク定義や CDK コードに平文を残さない。
- 既存 Construct 分割方針と `environment-config.ts` 中心の環境差分管理を維持し、破壊的変更を避ける。

## 変更対象
- `infra/lib/constructs/`（特に `todo-backend-ecs-service-construct.ts`）
  - Fargate TaskDefinition の 3 コンテナ化（`app` / `datadog-agent` / `log_router`）
  - app コンテナの log driver を `awsfirelens` へ変更
  - datadog-agent / log_router コンテナの `awslogs` 設定（診断用途）
  - `DD_*` / `OTEL_*` 環境変数・secret 注入の実装
- `infra/lib/config/environment-config.ts`
  - `DD_SITE`、`env`、共通タグ（`team` / `aws_account` / `system`）などの環境値定義の追加/整理
  - sidecar 用 CPU / memory、CloudWatch Logs 保持日数（dev=3, stg=7, prod=14 初期案）の定義
- `infra/lib/constructs/backend-image-deployment-construct.ts`
  - `DD_VERSION` と `backendDockerImageAsset.imageTag` の対応を実装で固定（または参照線を明示）
- `docs/backend/` または `docs/development/`（必要に応じて）
  - 運用手順（Datadog 検証手順、ログ保持、ローテーション責任）の追記

## 変更しないもの
- `backend/` 業務コード（Controller / Service / Repository / ドメインロジック）は変更しない。
- OpenTelemetry Logs API を業務ログの主経路にする変更は行わない。
- OTLP/HTTP を traces/metrics の主経路にする変更は行わない。
- 既存の認証・認可ロジック、DB スキーマ/Flyway マイグレーションは変更しない。

## 技術方針
- 既存パターン再利用
  - 現行の CDK Construct 分割・props 注入・`grantRead` パターンを再利用する。
  - 環境差分はハードコードせず、既存設定集約層（`environment-config.ts`）に寄せる。
- 新規コンポーネント
  - 既存 Construct を拡張して 3 コンテナを構成する方針を優先する。
  - 変更肥大化を避けるため、必要なら Datadog/FireLens 設定を補助関数化する（責務分離維持）。
- 新規設定
  - Datadog Agent: `ECS_FARGATE`、`DD_APM_ENABLED`、`DD_APM_NON_LOCAL_TRAFFIC`、`DD_CHECKS_TAG_CARDINALITY=orchestrator`、`DD_SITE`、`DD_ENV`、`DD_SERVICE`、`DD_VERSION`、`DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT`。
  - 非採用明示: `DD_LOGS_ENABLED` と `DD_OTLP_CONFIG_LOGS_ENABLED` は設定しない。
  - app: `OTEL_EXPORTER_OTLP_ENDPOINT`、`OTEL_TRACES_EXPORTER`、`OTEL_METRICS_EXPORTER`、`OTEL_LOGS_EXPORTER`、`OTEL_SERVICE_NAME`、`OTEL_RESOURCE_ATTRIBUTES`。
- 新規依存
  - 原則追加しない。FireLens は ECS 定義で有効化し、追加ライブラリ導入は避ける。
- 既存インフラ/契約
  - ADR-0001/0003 と本 spec を契約源として実装し、逸脱時は docs/ADR を先に更新してから反映する。
  - Datadog Site は `docs/adr/adr-0003-OTel-DatadogAgent-settings.md` に定義された `DD_SITE` 値（`datadoghq.com`）を正式値として扱う。

## データや契約への影響
- DB スキーマ: 影響なし。
- API 契約: 影響なし。
- イベント契約: 影響なし。
- 環境変数:
  - 追加/必須化: `DD_SITE`、`DD_ENV`、`DD_SERVICE`、`DD_VERSION`、`DD_APM_NON_LOCAL_TRAFFIC`、`DD_CHECKS_TAG_CARDINALITY` ほか。
  - app 側で `OTEL_SERVICE_NAME` と `DD_SERVICE` の同値運用を契約化。
- Secret / 設定値:
  - `DD_API_KEY` は Secrets Manager 由来で `datadog-agent` の `secrets` と FireLens の `secretOptions` へ注入する。
  - Secret 名は `/<environment>/<service>/datadog/api-key` 形式で運用し、例として `/prod/todo-backend/datadog/api-key` を採用する。
  - ローテーションは年1回以上の手動実施とし、責任者は当該シークレットへのアクセス権限を持つ担当者とする。
  - Datadog Application Key は本要件では未対応とする。
- デプロイ/運用:
  - タスク定義更新により再デプロイが必要。
  - `DD_VERSION=imageTag` の整合が運用トレーサビリティに直結するため、デプロイ手順に明記が必要。
  - Unified Service Tagging の正式タグ値は `team=o11y-CoE`、`service-name=todo-backend`、`system-name=todo` を採用する。

## リスク
- 破壊的変更リスク
  - log driver 切替（`awslogs`→`awsfirelens`）時の設定不備で app ログ欠損が起こり得る。
- 互換性リスク
  - Datadog Site と FireLens `Host` の不一致でログ送信失敗が起こり得る。
  - `DD_SERVICE` / `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES.service.name` の不整合で相関が崩れる。
- 運用リスク
  - sidecar 分の CPU / memory 見積不足で throttling / ログロストが発生し得る。
  - CloudWatch Logs 保持日数の設定漏れでコスト超過リスクがある。
- セキュリティリスク
  - `DD_API_KEY` の平文定義混入、不要な secret 読み取り権限付与。
- 監視リスク
  - Datadog index / exclusion / quota 未定義のまま本番投入すると、可観測性とコストの両面で不安定化する。

## 検証方針
- CDK 構成検証
  - `cdk synth` でタスク定義に 3 コンテナが出力されることを確認する。
  - app が `awsfirelens`、sidecar が `awslogs` であることをテンプレート差分で確認する。
  - `DD_API_KEY` が平文環境変数ではなく secret 参照になっていることを確認する。
- 実行時検証（dev 環境）
  - デプロイ後、Datadog Logs で `service/env/version` 付き app ログを確認する。
  - Datadog APM/Metrics で OTLP/gRPC 経由データ到達（`service:todo-backend`）を確認する。
  - Logs ↔ Traces 相関（`trace_id` / `span_id`）を確認する。
- 運用検証
  - `log_router` / `datadog-agent` の CloudWatch 保持日数が環境別設定どおりであることを確認する。
  - Datadog index/exclusion/quota の初期値反映を運用手順で確認する。


## ドキュメント更新方針
- `specs/004-Datadog-agent-to-cdk/`
  - 本 `plan.md` を基準に `tasks.md`（別工程）へ分解する。
- `docs/backend/`
  - バックエンドの OTel 設定と ECS 注入値の対応表が不足する場合は追記する。
- `docs/development/`
  - 環境構築/デプロイ手順に Datadog secret 事前作成手順と確認手順が不足する場合は追記する。
- `docs/adr/`
  - 方針変更がない限り ADR 新規追加は不要。方針変更時のみ ADR 更新を実施する。

## 実施順序
1. 事前確定（設計入力の固定）
   - Datadog Site は ADR-0003 の `DD_SITE`（`datadoghq.com`）を正式値として固定する。
   - `DD_API_KEY` は `/<environment>/<service>/datadog/api-key` 形式の Secrets Manager へ保管し、年1回以上の手動ローテーション運用を確定する。
   - Datadog Application Key は本要件の対象外として固定する。
   - sidecar CPU / memory 推奨値（`datadog-agent`: 256/512/予約256、`log_router`: 64/128/予約64）を初期標準値として固定する。
   - Unified Service Tagging の正式値（`o11y-CoE` / `todo-backend` / `todo`）を固定する。
2. CDK 実装
   - ECS TaskDefinition を 3 コンテナ構成へ拡張し、各コンテナの log driver/環境変数/secret を実装する。
   - `environment-config.ts` に環境差分パラメータを集約し、`DD_VERSION` の参照線を統一する。
3. 検証と運用設定
   - `cdk synth` と dev デプロイ検証で logs/traces/metrics 相関と secret 注入を確認する。
   - compliance logs はサンプルアプリケーション方針として通常ログと同じ保持日数で運用する。
   - APM sampling は環境別推奨（prod 10〜20%、stg 50%、dev 100%）を適用し、`DD_APM_MAX_TPS=10` / `DD_APM_ERROR_TPS=20` を設定する。
   - 月次コスト上限（インデックス済みスパン数: 月1万、APMホスト数: 10台、ログ取り込み量: 月100GB、Datadog契約: Enterprise）を運用監視の基準値として適用する。
   - Datadog 側 index/exclusion/quota・モニタ・ダッシュボード初期設定を適用し、運用手順を文書化する。
