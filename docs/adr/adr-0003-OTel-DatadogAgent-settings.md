# ADR-0003: Datadog Agent / FireLens / Spring Boot O11y 設定方針

- Status: Accepted
- Date: 2026-05-28
- Last Updated: 2026-05-28
- Decision owner: TBD
- Reviewers: TBD
- Supersedes: N/A
- Superseded by: N/A
- Related docs: `docs/infra/o11y.md`, `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`

## 1. 背景

`logs / metrics / trace` の経路と設定値が実装変更に追随できていない箇所があり、`docs/infra/o11y.md` と ADR 間で不整合が発生していた。  
特に Spring Boot 側の observability API 方針（Micrometer と OTel の責務分担）を明文化する必要がある。

## 2. 決定

### 2.1 Spring Boot の observability API 方針

- Spring Boot の業務メトリクスは Micrometer API（`MeterRegistry`）を標準とする。
- trace は OpenTelemetry exporter を使う（OTLP/gRPC 4317）。
- metrics は Micrometer OTLP Registry を使う（OTLP/HTTP 4318）。
- `OTEL_LOGS_EXPORTER` は `none` とし、アプリログの OTLP logs 送信は使わない。
- アプリ側で OTel Metrics API と Micrometer API を二重運用しない。

### 2.2 ログ経路方針

- `TodoBackendContainer` のログドライバは `awsfirelens` とする。
- アプリログは FireLens 経由で Datadog Logs に送る。
- `DatadogAgentContainer` と `LogRouterContainer` は `awslogs` とし、sidecar 診断ログを CloudWatch Logs に保存する。

## 3. Datadog Agent 設定（現行）

| 設定 | 値 | 必要性 | 用途 | 備考 |
| --- | --- | --- | --- | --- |
| `DD_API_KEY` | Secrets Manager から注入 | 必須 | Datadog 認証 | Secret 名: `/<env>/todo-backend/datadog/api-key` |
| `DD_SITE` | `datadoghq.com` | 必須 | Datadog 送信先 | US1 |
| `ECS_FARGATE` | `true` | 推奨 | Fargate 最適化 |  |
| `DD_APM_ENABLED` | `true` | 必須 | APM/trace 受信 |  |
| `DD_APM_NON_LOCAL_TRAFFIC` | `true` | 必須 | app コンテナから受信 | sidecar 構成で必要 |
| `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT` | `0.0.0.0:4317` | 必須 | OTLP/gRPC 受信 | trace 主経路 |
| `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT` | `0.0.0.0:4318` | 必須 | OTLP/HTTP 受信 | metrics 主経路 |
| `DD_SERVICE` | `todo-backend` | 必須 | Unified Service Tagging |  |
| `DD_ENV` | `dev / stg / prod` | 必須 | Unified Service Tagging | CDK `-c env=` と一致 |
| `DD_VERSION` | `DockerImageAsset.imageTag` | 必須 | Unified Service Tagging | デプロイ世代識別 |
| `DD_TAGS` | `team:o11y-CoE,system:todo,aws_account:<account-id>` | 推奨 | 補助タグ | `service/env/version` は重複定義しない |
| `DD_CHECKS_TAG_CARDINALITY` | `orchestrator` | 推奨 | ECS タスク粒度タグ付与 | `task_arn` など |
| `DD_APM_MAX_TPS` | `2` | 調整値 | APM スループット制御 | 現行設定値 |
| `DD_APM_ERROR_TPS` | `10` | 調整値 | エラートレース制御 | 現行設定値 |
| `DD_LOGS_ENABLED` | 設定しない | 方針 | Agent logs 収集無効 | logs は FireLens 主経路 |
| `DD_OTLP_CONFIG_LOGS_ENABLED` | 設定しない | 方針 | OTLP logs 受信無効 | 予期しないログ課金回避 |

## 4. Spring Boot / OTel / Micrometer 設定（現行）

| 設定 | 値 | 用途 | 備考 |
| --- | --- | --- | --- |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | `http://localhost:4317` | trace 送信先 | gRPC 側エンドポイント |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT` | `grpc` | trace プロトコル |  |
| `MANAGEMENT_OTLP_METRICS_EXPORT_URL` | `http://localhost:4318/v1/metrics` | metrics 送信先 | HTTP 固定 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OTLP 既定送信先 | trace 用 |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | OTLP 既定プロトコル |  |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `http://localhost:4318/v1/metrics` | metrics 個別送信先 | 4317 誤送信防止 |
| `OTEL_TRACES_EXPORTER` | `otlp` | trace exporter 有効化 |  |
| `OTEL_METRICS_EXPORTER` | `otlp` | metrics exporter 有効化 | 補助設定（主経路は `MANAGEMENT_OTLP_METRICS_EXPORT_URL`） |
| `OTEL_LOGS_EXPORTER` | `none` | logs exporter 無効化 | アプリログは FireLens |
| `OTEL_SERVICE_NAME` | `todo-backend` | service 名統一 | `DD_SERVICE` と一致 |
| `OTEL_RESOURCE_ATTRIBUTES` | `service.name=todo-backend,service.version=<DD_VERSION>,deployment.environment=<DD_ENV>` | 相関タグ統一 | logs/traces/metrics 横断キー |
| `DD_SERVICE` | `todo-backend` | Unified Service Tagging |  |
| `DD_ENV` | `dev / stg / prod` | Unified Service Tagging |  |
| `DD_VERSION` | `DockerImageAsset.imageTag` | Unified Service Tagging |  |

## 5. コンテナ別ログドライバ設定（現行）

| コンテナ | logDriver | 主な送信先 | CloudWatch Logs への出力 |
| --- | --- | --- | --- |
| `TodoBackendContainer` | `awsfirelens` | Datadog Logs | 原則なし（直接送信しない） |
| `LogRouterContainer` | `awslogs` | CloudWatch Logs | あり（Fluent Bit 診断ログ） |
| `DatadogAgentContainer` | `awslogs` | CloudWatch Logs | あり（Agent 診断ログ） |

## 6. sidecar リソースと保持期間（現行）

### 6.1 sidecar リソース

| コンテナ | CPU | Memory Limit | Memory Reservation |
| --- | --- | --- | --- |
| `DatadogAgentContainer` | 256 CPU units | 512 MiB | 256 MiB |
| `LogRouterContainer` | 64 CPU units | 128 MiB | 64 MiB |

### 6.2 CloudWatch Logs 保持期間

| 環境 | `datadog-agent` ログ | `log_router` ログ |
| --- | --- | --- |
| `dev` | 3 日 | 3 日 |
| `stg` | 7 日 | 7 日 |
| `prod` | 14 日 | 14 日 |

## 7. 影響

- `docs/infra/o11y.md` と本 ADR の整合が取れ、設定判断の基準を一本化できる。
- Spring Boot の業務メトリクス実装方針を Micrometer に固定でき、計装二重化のリスクを下げられる。
- CloudWatch Logs の役割（app ログではなく sidecar 診断ログ）が明確になる。

## 8. 参考資料

- Spring Boot Observability  
  https://docs.spring.io/spring-boot/reference/actuator/observability.html
- Spring Boot Metrics  
  https://docs.spring.io/spring-boot/reference/actuator/metrics.html
- Spring Framework Observability（Micrometer Observation）  
  https://docs.spring.io/spring-framework/reference/integration/observability.html
- Micrometer OTLP  
  https://docs.micrometer.io/micrometer/reference/implementations/otlp.html
- Datadog Agent OTLP Ingestion  
  https://docs.datadoghq.com/opentelemetry/setup/otlp_ingest_in_the_agent/
