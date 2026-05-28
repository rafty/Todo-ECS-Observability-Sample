# Spec: Datadog Agent to CDK（ECS/Fargate オブザーバビリティ要件）

## 概要
- AWS CDK（`infra/`）で、ECS Fargate タスクに `app` / `datadog-agent` / `log_router` を組み込み、Datadog で logs / traces / metrics を横断運用できる状態を定義する。
- ログは FireLens 経由で Datadog Logs、traces / metrics は OTLP/gRPC 経由で Datadog Agent に送る。

## 背景
- 現状は CloudWatch Logs 側の確認が中心で、`service` / `env` / `version` を軸にした Datadog 横断調査の運用要件が明文化されていない。
- `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md` で Datadog 主運用・FireLens と Agent の役割分離は採択済みであり、infra 実装要件としての具体化が必要である。

## 目的
- ECS タスクで sidecar 構成（Datadog Agent + FireLens）を標準化し、Datadog 上で調査可能な最小要件を確立する。
- シークレット管理、タグ統一、ログ保持方針を明確化し、運用コストとセキュリティリスクを抑制する。

## スコープ
- `infra/` の CDK 実装（ECS タスク定義、コンテナ環境変数、secret 注入、CloudWatch Logs 保持設定）。
- Datadog Agent 側の OTLP receiver 設定、および FireLens の Datadog Logs 転送設定。
- Datadog の運用前提（ログインデックス/除外、モニタ、ダッシュボード）の要件化。

## 対象外
- `backend/` 業務コードの追加変更（`@WithSpan` 追加、業務ログ API 変更など）。
- OpenTelemetry Logs API を業務ログの主経路にする構成。
- OTLP/HTTP を traces / metrics の主経路として採用する構成。

## ユーザーストーリー / 利用シナリオ
- 運用担当者として、Datadog だけで `service:todo-backend env:<env> version:<version>` を軸に logs / traces / metrics を辿りたい。
- インフラ担当者として、`dev/stg/prod` で同一方針の sidecar 構成を CDK で再現し、設定差分を最小化したい。
- セキュリティ担当者として、Datadog API key を平文にせず、Secrets Manager 経由で扱いたい。

## 機能要件
- ECS Fargate タスク定義に `app` / `datadog-agent` / `log_router` の 3 コンテナを含める。
- Datadog Agent コンテナは以下を満たす。
  - `ECS_FARGATE=true`、`DD_APM_ENABLED=true`、`DD_SITE`、`DD_ENV`、`DD_SERVICE`、`DD_VERSION` を注入する。
  - sidecar 構成で app コンテナからトレース受信できるよう、`DD_APM_NON_LOCAL_TRAFFIC=true` を設定する。
  - ECS/Fargate で `task_arn` などのオーケストレーター粒度タグを連携するため、`DD_CHECKS_TAG_CARDINALITY=orchestrator` を設定する。
  - `DD_API_KEY` は Secrets Manager 由来の secret として注入する（平文禁止）。
  - OTLP receiver は gRPC を有効化し、`DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT=0.0.0.0:4317` を設定する。
  - `DD_LOGS_ENABLED` は設定しない（ログ収集は FireLens を主経路とし、Agent 経由ログ収集を有効化しない）。
  - `DD_OTLP_CONFIG_LOGS_ENABLED=true` は設定しない（OTLP logs ingestion は非採用）。
- app コンテナは以下を満たす。
  - `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`、`OTEL_TRACES_EXPORTER=otlp`、`OTEL_METRICS_EXPORTER=otlp`、`OTEL_LOGS_EXPORTER=none` を注入する。
  - `OTEL_SERVICE_NAME` と `DD_SERVICE` を同値にする。
  - `OTEL_RESOURCE_ATTRIBUTES` に `service.version=<version>` と `deployment.environment=<env>` を含める。
- FireLens / Fluent Bit は以下を満たす。
  - `log_router` コンテナを追加し、app コンテナの log driver を `awsfirelens` にする。
  - app ログの出力先を Datadog Logs に設定し、`dd_service` / `dd_source` / `dd_tags` を付与する。
  - Datadog API key は FireLens の `secretOptions` から注入する。
- ログドライバ制約を遵守する。
  - 同一コンテナに `awslogs` と `awsfirelens` を同時設定しない。
  - app は `awsfirelens`、`datadog-agent` / `log_router` は `awslogs` を許容する。
- Datadog タグは Unified Service Tagging を満たす。
  - 必須軸: `env` / `service` / `version`。
  - 拡張タグ: `team`、`aws_account`、`system`（`DD_TAGS`）を追加する。
  - `DD_SERVICE` / `DD_ENV` / `DD_VERSION` を `DD_TAGS` に重複定義しない。

## 非機能要件
- 運用/コスト
  - application logs は Datadog 主保管とし、CloudWatch 全量長期保持を避ける。
  - `log_router` / `datadog-agent` の CloudWatch Logs 保持日数を環境別に管理する（dev=3日, stg=7日, prod=14日を初期案とする）。
  - Datadog Logs の index/exclusion/retention/daily quota と使用量監視を定義する。
- 性能/容量
  - sidecar 分の CPU / memory をタスク定義へ見積もる。
  - 高カーディナリティタグの乱立を防止する。
- セキュリティ
  - API key、Application key、トークン類はすべて secret 管理とする。
  - ログへ機密値を出力しない。

## 受け入れ条件
- ECS タスク定義に `app` / `datadog-agent` / `log_router` が含まれ、3 コンテナが起動可能である。
- app は `awsfirelens` で Datadog Logs に転送され、Datadog 上で `service` / `env` / `version` で検索できる。
- traces / metrics は OTLP/gRPC（4317）経由で Datadog APM/Metrics に到達する。
- Datadog API key は `secrets` / `secretOptions` 経由で注入され、平文定義がない。
- Datadog 上で Logs ↔ Traces 相関（`trace_id` / `span_id`）が確認できる。
- CloudWatch Logs 保持は定義済みの限定用途（sidecar 診断など）に制御される。

## 制約
- `env` は `dev|stg|prod` の既存運用に整合させる。
- 既存 CDK 構成（`environment-config.ts`、Construct 分割、タグ方針）を維持し、破壊的変更を避ける。
- 本仕様は infra 主体であり、backend 側変更は別仕様（`specs/003-OTel-to-backend-fix-02/`）を前提とする。

## 依存関係
- `infra/lib/config/environment-config.ts`（環境値・共通タグ値）。
- `infra/lib/constructs/backend-image-deployment-construct.ts`（`DD_VERSION` の基準候補）。
- `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`（全体方針）。
- `specs/003-OTel-to-backend-fix-02/specs-draft.md`（backend 側契約条件）。
- Datadog 側設定（Logs index、Exclusion Filter、Monitor、Dashboard）の運用設計。

## 未確定事項 / 要確認事項
- Datadog 側で事前確定が必要な情報
  - Datadog Site（`datadoghq.com` / `us3.datadoghq.com` / `us5.datadoghq.com` / `eu.datadoghq.com` / `ap1.datadoghq.com` / `ddog-gov.com` のいずれか）。
  - Datadog Organization 名または組織識別子（運用対象アカウント）。
  - Datadog API Key の払い出し先、Secrets Manager 名/ARN、ローテーション責任者。
  - Datadog Application Key の要否（Dashboard/Monitor/Index を API/IaC 管理する場合）と最小権限スコープ。
  - Datadog Logs インデックス方針（対象ログ、retention、daily quota、exclusion 条件）。
- AWS/運用側で確定が必要な情報
  - sidecar（`datadog-agent` / `log_router`）の CPU / memory 標準値。
  - compliance logs の保持日数（法令・契約要件）。
  - APM sampling rate とコスト上限。
  - `team` / `service-name` / `system-name` の正式値（環境共通値）。
- ドラフト分析で判明した整合性リスク・技術的要確認点
  - `docs/adr/adr-0001` / `docs/adr/adr-0002` / 本 spec は、traces / metrics の経路を OTLP/gRPC（`http://localhost:4317`）で統一する。
  - FireLens の `Host` は Datadog Site ごとに異なるため、`http-intake.logs.datadoghq.com` 固定は誤設定リスクがある。Site 別 endpoint を確定する必要がある。
  - `DD_VERSION` を `backendDockerImageAsset.imageTag` に統一する方針は妥当だが、デプロイ単位（タスク再作成タイミング）との整合を運用手順として明文化する必要がある。
