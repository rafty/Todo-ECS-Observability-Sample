# ADR-0003: Datadog Agent / FireLens / Spring Boot O11y 設定方針

- Status: Accepted（ADR-0004 により trace / metrics export 方針は一部 superseded）
- Date: 2026-05-28
- Last Updated: 2026-07-27
- Decision owner: TBD
- Reviewers: TBD
- Supersedes: N/A
- Superseded by: ADR-0004（OpenTelemetry Java Agent 導入により Spring Boot OTel starter / Micrometer OTLP Registry 中心の trace / metrics export 方針を置き換える）
- Related docs: `docs/infra/o11y.md`, `docs/backend/logging.md`, `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`, `docs/adr/adr-0004-OTel-Java-Agent.md`

## 1. 背景

`logs / metrics / trace` の経路と設定値が実装変更に追随できていない箇所があり、`docs/infra/o11y.md` と ADR 間で不整合が発生していた。
特に Spring Boot 側の observability API 方針（Micrometer と OpenTelemetry の責務分担）を明文化する必要があった。

その後、ADR-0004 により OpenTelemetry Java Agent の採用が決定された。これにより、ADR-0003 のうち Spring Boot OTel starter / Micrometer OTLP Registry を trace / metrics export の主経路とする前提は変更される。

## 2. 現在も有効な決定

### 2.1 ログ経路方針

- `TodoBackendContainer` のログドライバは `awsfirelens` とする。
- アプリログは FireLens 経由で Datadog Logs に送る。
- `DatadogAgentContainer` と `LogRouterContainer` は `awslogs` とし、sidecar 診断ログを CloudWatch Logs に保存する。
- `OTEL_LOGS_EXPORTER` は `none` とし、アプリログの OTLP logs 送信は使わない。

### 2.2 業務 metrics API 方針

- アプリ内の業務 metrics は Micrometer API（`MeterRegistry`）を標準とする。
- `BusinessMetricsService` の `todo.operation.count` / `todo.operation.duration` は維持する。
- OpenTelemetry Metrics API と Micrometer API を業務 metrics で無条件に二重運用しない。

## 3. ADR-0004 により置き換える決定

ADR-0003 初版では、trace は Spring Boot OTel exporter、metrics は Micrometer OTLP Registry を使う方針だった。OpenTelemetry Java Agent 導入後は、以下に置き換える。

| 項目 | ADR-0003 初版 | ADR-0004 後 |
| --- | --- | --- |
| trace export | Spring Boot OTel exporter | OpenTelemetry Java Agent OTLP exporter |
| metrics export | Micrometer OTLP Registry | OpenTelemetry Java Agent OTLP metrics exporter |
| JDBC span | 個別の手動/追加計装が必要 | Java Agent 自動計装 |
| JDBC / database metrics | 個別の手動/追加計装が必要 | Java Agent 自動計装 |
| Runtime Metrics | 個別の手動/追加計装が必要 | Java Agent runtime telemetry |
| 業務 metrics | Micrometer API | Micrometer API 維持。Java Agent Micrometer instrumentation 経由で export を試す |
| logs | FireLens | FireLens 維持 |

## 4. Datadog Agent 設定（Java Agent 導入後）

| 設定 | 値 | 必要性 | 用途 | 備考 |
| --- | --- | --- | --- | --- |
| image | `public.ecr.aws/datadog/agent:7.81.2` | 必須 | Datadog Agent 実行 image | `latest` は使わない |
| `DD_API_KEY` | Secrets Manager から注入 | 必須 | Datadog 認証 | Secret 名: `/<env>/todo-backend/datadog/api-key` |
| `DD_SITE` | `datadoghq.com` | 必須 | Datadog 送信先 | US1 |
| `ECS_FARGATE` | `true` | 推奨 | Fargate 最適化 |  |
| `DD_APM_ENABLED` | `true` | 必須 | APM/trace 受信 |  |
| `DD_APM_NON_LOCAL_TRAFFIC` | `true` | 必須 | app コンテナから受信 | sidecar 構成で必要 |
| `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT` | `0.0.0.0:4317` | 必須 | OTLP/gRPC 受信 | trace 主経路 |
| `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT` | `0.0.0.0:4318` | 必須 | OTLP/HTTP 受信 | metrics 主経路 |
| `DD_APM_IGNORE_RESOURCES` | `^GET /actuator/health(/.*)?$,^HEAD /actuator/health(/.*)?$` | 必須 | health check trace 除外 | canary 後に Datadog APM resource 名と照合する |
| `DD_SERVICE` | `todo-backend` | 必須 | Unified Service Tagging |  |
| `DD_ENV` | `dev / stg / prod` | 必須 | Unified Service Tagging | CDK `-c env=` と一致 |
| `DD_VERSION` | `DockerImageAsset.imageTag` | 必須 | Unified Service Tagging | デプロイ世代識別 |
| `DD_TAGS` | `team:o11y-CoE,system:todo,aws_account:<account-id>` | 推奨 | 補助タグ | `service/env/version` は重複定義しない |
| `DD_CHECKS_TAG_CARDINALITY` | `orchestrator` | 推奨 | ECS タスク粒度タグ付与 | `task_arn` など |
| `DD_APM_MAX_TPS` | `2` | 調整値 | APM スループット制御 | 初期値維持。canary 後に調整判断 |
| `DD_APM_ERROR_TPS` | `10` | 調整値 | エラートレース制御 | 初期値維持 |
| `DD_LOGS_ENABLED` | 設定しない | 方針 | Agent logs 収集無効 | logs は FireLens 主経路 |
| `DD_OTLP_CONFIG_LOGS_ENABLED` | 設定しない | 方針 | OTLP logs 受信無効 | 予期しないログ課金回避 |

## 5. OpenTelemetry Java Agent 設定（Java Agent 導入後）

| 設定 | 値 | 用途 | 備考 |
| --- | --- | --- | --- |
| `JAVA_TOOL_OPTIONS` | `-javaagent:/app/opentelemetry-javaagent.jar` | Java Agent attach | ECS task definition で設定 |
| `OTEL_TRACES_EXPORTER` | `otlp` | trace exporter 有効化 |  |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4317` | trace 送信先 | Datadog Agent sidecar の OTLP/gRPC receiver |
| `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL` | `grpc` | trace protocol | signal 別に明示 |
| `OTEL_METRICS_EXPORTER` | `otlp` | metrics exporter 有効化 |  |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `http://localhost:4318/v1/metrics` | metrics 送信先 | Datadog Agent sidecar の OTLP/HTTP receiver |
| `OTEL_EXPORTER_OTLP_METRICS_PROTOCOL` | `http/protobuf` | metrics protocol | signal 別に明示 |
| `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE` | `delta` | metrics temporality | Datadog OTLP metrics ingest 向け |
| `OTEL_LOGS_EXPORTER` | `none` | logs exporter 無効化 | アプリログは FireLens |
| `OTEL_SEMCONV_STABILITY_OPT_IN` | `database` | JDBC / database semantic conventions |  |
| `OTEL_INSTRUMENTATION_MICROMETER_ENABLED` | `true` | Micrometer metrics instrumentation | `todo.operation.*` の export を canary で確認 |
| `OTEL_INSTRUMENTATION_RUNTIME_TELEMETRY_ENABLED` | `true` | Runtime Metrics | JVM memory / GC / thread / CPU など |
| `OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED` | `true` | SQL statement sanitizer | bind parameter 等の露出防止 |
| `OTEL_SERVICE_NAME` | `todo-backend` | service 名統一 | `DD_SERVICE` と一致 |
| `OTEL_RESOURCE_ATTRIBUTES` | `service.name=todo-backend,service.version=<DD_VERSION>,deployment.environment=<DD_ENV>` | 相関タグ統一 | traces / metrics 横断キー |
| `DD_SERVICE` | `todo-backend` | Unified Service Tagging |  |
| `DD_ENV` | `dev / stg / prod` | Unified Service Tagging |  |
| `DD_VERSION` | `DockerImageAsset.imageTag` | Unified Service Tagging |  |

削除対象となった旧設定:

- `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`
- `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT`
- `MANAGEMENT_OTLP_METRICS_EXPORT_URL`
- generic `OTEL_EXPORTER_OTLP_ENDPOINT`
- generic `OTEL_EXPORTER_OTLP_PROTOCOL`

## 6. コンテナ別ログドライバ設定

| コンテナ | logDriver | 主な送信先 | CloudWatch Logs への出力 |
| --- | --- | --- | --- |
| `TodoBackendContainer` | `awsfirelens` | Datadog Logs | 原則なし（直接送信しない） |
| `LogRouterContainer` | `awslogs` | CloudWatch Logs | あり（Fluent Bit 診断ログ） |
| `DatadogAgentContainer` | `awslogs` | CloudWatch Logs | あり（Agent 診断ログ） |

## 7. sidecar リソースと保持期間

### 7.1 sidecar リソース

| コンテナ | CPU | Memory Limit | Memory Reservation |
| --- | --- | --- | --- |
| `DatadogAgentContainer` | 256 CPU units | 512 MiB | 256 MiB |
| `LogRouterContainer` | 64 CPU units | 128 MiB | 64 MiB |

### 7.2 CloudWatch Logs 保持期間

| 環境 | `datadog-agent` ログ | `log_router` ログ |
| --- | --- | --- |
| `dev` | 3 日 | 3 日 |
| `stg` | 7 日 | 7 日 |
| `prod` | 14 日 | 14 日 |

## 8. 影響

- `docs/infra/o11y.md` と本 ADR の整合が取れ、Java Agent 導入後の設定判断の基準を一本化できる。
- Spring Boot OTel starter / Micrometer OTLP Registry の exporter 二重運用リスクを下げられる。
- 業務 metrics のアプリ内 API は Micrometer に維持し、Datadog への送信責務を Java Agent に寄せる。
- CloudWatch Logs の役割（app ログではなく sidecar 診断ログ）が引き続き明確になる。

## 9. 確認事項

- `todo.operation.*` が Java Agent Micrometer instrumentation 経由で Datadog Metrics に届くかは canary deploy 後に確認する。
- Java Agent Logback MDC instrumentation だけで JSON ログトップレベルに `trace_id` / `span_id` が出るかは canary deploy 後に確認する。
- Runtime Metrics と既存 Micrometer JVM metrics の重複有無は Datadog 上で確認する。
- `DD_APM_IGNORE_RESOURCES` の regex が `/actuator/health` だけを除外し、業務 API を除外していないことを canary で確認する。

## 10. 参考資料

- OpenTelemetry Java Agent
  https://opentelemetry.io/docs/zero-code/java/agent/
- OpenTelemetry Java Agent Configuration
  https://opentelemetry.io/docs/zero-code/java/agent/configuration/
- Datadog Agent OTLP Ingestion
  https://docs.datadoghq.com/opentelemetry/setup/otlp_ingest_in_the_agent/
- Datadog OpenTelemetry Runtime Metrics
  https://docs.datadoghq.com/opentelemetry/integrations/runtime_metrics/?tab=java
