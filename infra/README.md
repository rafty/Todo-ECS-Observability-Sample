# infra

このディレクトリは AWS CDK（TypeScript）でインフラを定義する領域です。

## 前提

- Node.js
- AWS CLI
- AWS CDK v2
- Docker（`cdk synth/diff/deploy` 時の backend イメージビルドに必要）
- 対象アカウントへアクセス可能な AWS 認証情報

## 主要コマンド

`infra/` 直下で実行します。

```bash
npm ci
npm run build
npm test -- --runInBand
npx cdk synth -c env=prod
npx cdk diff -c env=prod
npx cdk deploy -c env=prod
```

## 環境切替ルール

- 環境は `-c env=<dev|stg|prod>` で指定する。
- 環境値（`accountId` / `region` / Datadog 設定）は `lib/config/environment-config.ts` で管理する。
- 本 feature の検証対象環境はユーザー指定により `prod` とする。

## O11y 構成（OpenTelemetry Java Agent 導入後）

### ECS タスク内コンテナ

| コンテナ | 役割 | log driver | 主な送信先 |
| --- | --- | --- | --- |
| `TodoBackendContainer` | Spring Boot API。OpenTelemetry Java Agent を attach して traces / metrics を生成する | `awsfirelens` | Datadog Logs |
| `DatadogAgentContainer` | OTLP receiver / Datadog 送信 sidecar | `awslogs` | CloudWatch Logs（診断ログ） |
| `LogRouterContainer` | FireLens / Fluent Bit log router | `awslogs` | CloudWatch Logs（診断ログ） |

### Signal 別経路

| Signal | 生成元 | ECS task 内の経路 | Datadog 側 |
| --- | --- | --- | --- |
| logs | SLF4J / Logback JSON | app stdout -> FireLens | Datadog Logs |
| Trace / Span（自動計装） | OpenTelemetry Java Agent | app -> OTLP/gRPC `localhost:4317` -> Datadog Agent | Datadog APM |
| Trace / Span（業務/手動計装） | `TodoOperationTelemetryAspect` + OpenTelemetry API | app -> OTLP/gRPC `localhost:4317` -> Datadog Agent | Datadog APM |
| Metrics（JDBC / Runtime / Micrometer） | OpenTelemetry Java Agent | app -> OTLP/HTTP `localhost:4318/v1/metrics` -> Datadog Agent | Datadog Metrics |

同じ API リクエストから logs / traces / metrics が同時に作られることがありますが、ECS task 内の転送経路は分かれます。ログの `eventType` は Datadog Logs で検索するための JSON フィールドであり、OpenTelemetry の span 名や span event ではありません。ECS Service / Task の起動・停止イベントも AWS control plane 側のイベントであり、アプリログとは別に扱います。

### CDK で注入する主要環境変数

#### `TodoBackendContainer`

- `JAVA_TOOL_OPTIONS=-javaagent:/app/opentelemetry-javaagent.jar`
- `OTEL_TRACES_EXPORTER=otlp`
- `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4317`
- `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=grpc`
- `OTEL_METRICS_EXPORTER=otlp`
- `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=http://localhost:4318/v1/metrics`
- `OTEL_EXPORTER_OTLP_METRICS_PROTOCOL=http/protobuf`
- `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=delta`
- `OTEL_LOGS_EXPORTER=none`
- `OTEL_SEMCONV_STABILITY_OPT_IN=database`
- `OTEL_INSTRUMENTATION_MICROMETER_ENABLED=true`
- `OTEL_INSTRUMENTATION_RUNTIME_TELEMETRY_ENABLED=true`
- `OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED=true`
- `OTEL_SERVICE_NAME=todo-backend`
- `OTEL_RESOURCE_ATTRIBUTES=service.name=todo-backend,service.version=<imageTag>,deployment.environment=<env>`
- `DD_SERVICE=todo-backend`
- `DD_ENV=<env>`
- `DD_VERSION=<imageTag>`

Spring Boot OTel starter / Micrometer OTLP Registry 前提の `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_*` と `MANAGEMENT_OTLP_METRICS_EXPORT_URL` は使用しません。

#### `DatadogAgentContainer`

- Datadog Agent image: `public.ecr.aws/datadog/agent:7.81.2`
- `DD_API_KEY`（Secrets Manager 参照）
- `DD_SITE=datadoghq.com`
- `DD_APM_ENABLED=true`
- `DD_APM_NON_LOCAL_TRAFFIC=true`
- `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT=0.0.0.0:4317`
- `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318`
- `DD_APM_IGNORE_RESOURCES=^GET /actuator/health(/.*)?$,^HEAD /actuator/health(/.*)?$`
- `DD_APM_MAX_TPS=2`
- `DD_APM_ERROR_TPS=10`
- `DD_SERVICE=todo-backend`
- `DD_ENV=<env>`
- `DD_VERSION=<imageTag>`
- `DD_TAGS=team:o11y-CoE,system:todo,aws_account:<accountId>`

Datadog Agent image は `latest` を使わず、検証対象バージョンを `lib/config/environment-config.ts` で固定します。

### `/actuator/health` trace 除外

`/actuator/health` は ALB health check により高頻度で呼ばれるため、APM のノイズと取り込み量増加を避ける目的で `DD_APM_IGNORE_RESOURCES` を Datadog Agent sidecar に設定します。

初期値は以下です。

```text
^GET /actuator/health(/.*)?$,^HEAD /actuator/health(/.*)?$
```

この regex は Datadog APM 上の root span resource 名に依存するため、canary deploy 後に `/api/todos` など業務 API が誤って除外されていないことを確認します。

## シークレット

- Datadog API Key: `/<env>/todo-backend/datadog/api-key`
- DB 接続情報: `/todo/<env>/backend/database`

シークレット値は通常環境変数やコードへ埋め込まず、Secrets Manager 参照のまま維持します。

## 実行時の注意

- `frontend` 更新時は `infra` 実行前に `frontend/` で `npm run build` を実行する。
- `cdk deploy` は backend イメージ配布（ECR）とインフラ更新を同時に行う。
- OpenTelemetry Java Agent 導入後は app JVM の startup time、CPU、memory、GC、Datadog Agent の OTLP receiver error を canary で確認する。
- `npx cdk synth` を実行する場合、本 feature では `npx cdk synth -c env=prod` を使用する。

## 関連ドキュメント

- [docs 入口](../docs/README.md)
- [Observability 仕様](../docs/infra/o11y.md)
- [AWS デプロイ手順（Monorepo 全体）](../docs/development/aws-deployment-manual.md)
- [ADR ディレクトリ](../docs/adr/)
