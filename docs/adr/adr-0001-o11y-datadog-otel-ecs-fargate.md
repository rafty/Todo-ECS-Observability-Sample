# ADR-0001: ECS Fargate 上の Spring Boot アプリにおける Datadog / OpenTelemetry / ログ収集方式

- Status: Accepted
- Date: 2026-05-21
- Last Updated: 2026-05-28
- Decision owner: TBD
- Reviewers: TBD
- Related specs: `specs/004-Datadog-agent-to-cdk/specs.md`, `specs/004-Datadog-agent-to-cdk/plan.md`, `specs/004-Datadog-agent-to-cdk/tasks.md`
- Related ADR:
  - `docs/adr/adr-0002-trace-correlation-otel-datadog.md`
  - `docs/adr/adr-0003-OTel-DatadogAgent-settings.md`
- Related docs: `docs/infra/o11y.md`

## 1. 背景

本システムは `CloudFront -> ALB -> ECS(Fargate) -> Spring Boot` の構成で稼働している。  
従来は CloudWatch Logs を中心に調査していたが、`logs / traces / metrics` を横断する調査性、ならびに運用の一貫性を高めるため、Datadog を主画面とする設計に移行した。

同時に、以下の不整合を解消する必要があった。

- アプリログの配送経路（`awslogs` 前提）と Datadog 連携要件の差分
- Spring Boot 側のメトリクス API（Micrometer / OTel Metrics API）混在リスク
- `service/env/version` と `trace_id/span_id` を軸にした相関ルールの曖昧さ

## 2. 決定

### 2.1 シグナル経路

| Signal | アプリ側実装 | タスク内経路 | Datadog 送信先 | 備考 |
| --- | --- | --- | --- | --- |
| logs | SLF4J + Logback(JSON) | `TodoBackendContainer (awsfirelens)` -> `LogRouterContainer` | Datadog Logs | アプリログは CloudWatch Logs へ直接送信しない |
| metrics | Micrometer API (`MeterRegistry`) | OTLP/HTTP `http://localhost:4318/v1/metrics` -> `DatadogAgentContainer` | Datadog Metrics | `todo.operation.count`, `todo.operation.duration` |
| traces / span events | OTel tracer (`spring-boot-starter-opentelemetry`) | OTLP/gRPC `http://localhost:4317` -> `DatadogAgentContainer` | Datadog APM | span event は trace の一部として扱う |
| OTel logs | 使用しない | `OTEL_LOGS_EXPORTER=none` | なし | 予期しない二重課金を避ける |

### 2.2 ECS タスク構成

| コンテナ | 役割 | logDriver | CloudWatch Logs 出力 |
| --- | --- | --- | --- |
| `TodoBackendContainer` | API本体 | `awsfirelens` | なし（直接は出ない） |
| `LogRouterContainer` | FireLens / Fluent Bit | `awslogs` | あり（ルーター診断ログ） |
| `DatadogAgentContainer` | OTLP受信 / Datadog転送 | `awslogs` | あり（Agent診断ログ） |

CloudWatch Logs は「アプリ本体ログの主保管先」ではなく、「sidecar 診断ログの短期保持先」として使う。

### 2.3 API 方針（Spring Boot 側）

- trace: OTel exporter を使用し、OTLP/gRPC（4317）で Agent へ送る。
- metrics: Micrometer OTLP Registry を使用し、OTLP/HTTP（4318）で Agent へ送る。
- logs: OTel Logs exporter は無効（`OTEL_LOGS_EXPORTER=none`）、アプリログは FireLens 経路に統一する。
- 業務メトリクスは Micrometer API に統一し、OTel Metrics API と二重運用しない。

### 2.4 相関タグ方針

- Datadog Unified Service Tagging を標準化する。
  - `service=todo-backend`
  - `env=dev|stg|prod`
  - `version=<DockerImageAsset.imageTag>`
- OTel resource attributes は次を固定する。
  - `service.name=todo-backend`
  - `service.version=<DockerImageAsset.imageTag>`
  - `deployment.environment=<env>`
- ログ相関キーは `trace_id` / `span_id` を正とする（詳細は ADR-0002）。

## 3. 設定契約

### 3.1 TodoBackendContainer（主要な O11y 変数）

- `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://localhost:4317`
- `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT=grpc`
- `MANAGEMENT_OTLP_METRICS_EXPORT_URL=http://localhost:4318/v1/metrics`
- `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`
- `OTEL_EXPORTER_OTLP_PROTOCOL=grpc`
- `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=http://localhost:4318/v1/metrics`
- `OTEL_TRACES_EXPORTER=otlp`
- `OTEL_METRICS_EXPORTER=otlp`
- `OTEL_LOGS_EXPORTER=none`
- `OTEL_SERVICE_NAME=todo-backend`
- `OTEL_RESOURCE_ATTRIBUTES=service.name=todo-backend,service.version=<imageTag>,deployment.environment=<env>`
- `DD_SERVICE=todo-backend`
- `DD_ENV=<env>`
- `DD_VERSION=<imageTag>`

### 3.2 DatadogAgentContainer（主要な O11y 変数）

- `DD_API_KEY`（Secrets Manager `/<env>/todo-backend/datadog/api-key` から注入）
- `DD_SITE=datadoghq.com`
- `DD_APM_ENABLED=true`
- `DD_APM_NON_LOCAL_TRAFFIC=true`
- `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT=0.0.0.0:4317`
- `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318`
- `DD_SERVICE=todo-backend`
- `DD_ENV=<env>`
- `DD_VERSION=<imageTag>`
- `DD_TAGS=team:o11y-CoE,system:todo,aws_account:<account-id>`

## 4. 採用しない方針

- アプリコンテナを `awslogs` へ戻し、CloudWatch Logs を主経路にすること
- アプリログを FireLens と OTLP logs の二経路で送ること
- 業務メトリクスで OTel Metrics API と Micrometer API を混在させること
- `service/env/version` を複数定義してデータソースごとに揺らすこと

## 5. 運用確認基準

- Logs: `service:todo-backend env:<env> version:<imageTag>` で app ログが検索できる
- Metrics: `todo.operation.count`, `todo.operation.duration` が Datadog で確認できる
- Traces: `service:todo-backend env:<env>` で span が確認できる
- Correlation: `trace_id` / `span_id` で Logs ↔ Traces を相互遷移できる
- CloudWatch Logs: `log_router` / `datadog-agent` ロググループのみが生成され、保持日数が `dev=3 / stg=7 / prod=14`

## 6. 影響

### 6.1 良い影響

- Datadog を主画面として logs/traces/metrics を同一タグで横断できる。
- CloudWatch Logs の役割を sidecar 診断用途へ限定できる。
- 業務メトリクスの API 方針を Micrometer に統一し、実装の混乱を減らせる。

### 6.2 注意点

- Micrometer OTLP Registry は既定で HTTP 送信のため、metrics を gRPC に統一したい場合は別途 `OtlpMetricsSender` 実装が必要。
- FireLens 障害時は app ログが Datadog に到達しないため、`LogRouterContainer` の CloudWatch Logs を一次調査点にする。
- Agent 側受け口の 4317/4318 設定ミスは、metrics/traces の欠損を起こす。

## 7. 参考資料

- Datadog Unified Service Tagging  
  https://docs.datadoghq.com/getting_started/tagging/unified_service_tagging/
- Datadog OTLP Ingestion（Agent）  
  https://docs.datadoghq.com/opentelemetry/setup/otlp_ingest_in_the_agent/
- Datadog Logs and Traces Correlation（OpenTelemetry）  
  https://docs.datadoghq.com/opentelemetry/correlate/logs_and_traces/
- Spring Boot Metrics（Micrometer / OTLP）  
  https://docs.spring.io/spring-boot/reference/actuator/metrics.html
- Spring Boot Observability  
  https://docs.spring.io/spring-boot/reference/actuator/observability.html
