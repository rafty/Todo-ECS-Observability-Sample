# backend

Spring Boot ベースの Todo API（認証付きバックエンド）です。  
AWS 実行時は `CloudFront -> ALB -> ECS -> Aurora` の経路で稼働し、DB 接続情報は Secrets Manager から注入されます。

## 主な責務

- `/api/todos` の CRUD API を提供する。
- JWT（Cognito issuer）を検証し、`owner_subject` 境界でデータを分離する。
- Flyway による `todos` スキーマ管理を行う。
- ALB ヘルスチェック用に `/actuator/health` を公開する。
- OpenTelemetry Java Agent により Spring / JDBC / Runtime の自動計装を行う。
- Java Agent が自動生成しない Todo 業務操作 span と `todo.operation.*` metrics を限定的に手動計装する。

## 前提

- Java 21
- Maven（このディレクトリには Maven Wrapper は存在しない）
- ローカル起動時は PostgreSQL または互換環境
- Docker（コンテナビルド確認時）

## 主要コマンド

`backend/` 配下で実行します。

```bash
mvn test
mvn -DskipTests compile
mvn spring-boot:run
```

ローカル Java が複数ある場合は Java 21 を明示します。

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test
```

## OpenTelemetry Java Agent

コンテナイメージでは、`Dockerfile` の `otel-agent` build stage で Maven artifact として OpenTelemetry Java Agent を取得し、final image の `/app/opentelemetry-javaagent.jar` に同梱します。

- Agent artifact: `io.opentelemetry.javaagent:opentelemetry-javaagent`
- Agent version: `2.30.0`
- JVM attach: ECS task definition の `JAVA_TOOL_OPTIONS=-javaagent:/app/opentelemetry-javaagent.jar`
- 実行時 download、GitHub Releases 直接 download、`latest` 相当の取得は行わない。
- `opentelemetry-javaagent.jar` は application dependency ではなく、実行イメージに同梱する Java Agent として扱う。

## 主要設定（環境変数）

### DB 接続

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_HOST`
- `SPRING_DATASOURCE_PORT`
- `SPRING_DATASOURCE_DBNAME`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

ECS では DB 接続情報を Secrets Manager から `SPRING_DATASOURCE_*` へ注入します。

### JWT 検証

- `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`

### O11y（Datadog / OpenTelemetry）

| 変数名 | ECS での値 | 役割 |
| --- | --- | --- |
| `JAVA_TOOL_OPTIONS` | `-javaagent:/app/opentelemetry-javaagent.jar` | JVM 起動時に OpenTelemetry Java Agent を attach する |
| `OTEL_TRACES_EXPORTER` | `otlp` | trace exporter を OTLP にする |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4317` | trace を同一 ECS task 内の Datadog Agent OTLP/gRPC receiver へ送る |
| `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL` | `grpc` | trace の OTLP protocol を gRPC に固定する |
| `OTEL_METRICS_EXPORTER` | `otlp` | metrics exporter を OTLP にする |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `http://localhost:4318/v1/metrics` | metrics を同一 ECS task 内の Datadog Agent OTLP/HTTP receiver へ送る |
| `OTEL_EXPORTER_OTLP_METRICS_PROTOCOL` | `http/protobuf` | metrics の OTLP protocol を HTTP/protobuf に固定する |
| `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE` | `delta` | Datadog の OTLP metrics ingest に合わせて delta temporality を使う |
| `OTEL_LOGS_EXPORTER` | `none` | OTLP logs を無効化し、app logs は FireLens 経路に統一する |
| `OTEL_SEMCONV_STABILITY_OPT_IN` | `database` | JDBC / database metrics の semantic conventions を明示する |
| `OTEL_INSTRUMENTATION_MICROMETER_ENABLED` | `true` | 既存 Micrometer 業務 metrics を Java Agent 経由 export の対象にする |
| `OTEL_INSTRUMENTATION_RUNTIME_TELEMETRY_ENABLED` | `true` | JVM Runtime Metrics を Java Agent で取得する |
| `OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED` | `true` | SQL bind parameter などの機密値が span 属性へ出る事故を防ぐ |
| `OTEL_SERVICE_NAME` | `todo-backend` | OTel service 名を固定する |
| `OTEL_RESOURCE_ATTRIBUTES` | `service.name=todo-backend,service.version=<imageTag>,deployment.environment=<env>` | traces / metrics の service / version / env を統一する |
| `DD_SERVICE` | `todo-backend` | Datadog Unified Service Tagging |
| `DD_ENV` | `<env>` | Datadog Unified Service Tagging |
| `DD_VERSION` | `<imageTag>` | Datadog Unified Service Tagging。`infra/lib/constructs/backend-image-deployment-construct.ts` の image tag 由来 |

`MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_*` と `MANAGEMENT_OTLP_METRICS_EXPORT_URL` は Spring Boot OTel starter / Micrometer OTLP Registry 前提の設定であり、Java Agent 導入後の ECS task definition では使用しません。

## 手動計装の方針

Java Agent は HTTP server / Spring Web MVC / JDBC などの framework / library span を自動生成します。一方で、任意の業務メソッドをすべて自動 span 化するものではありません。

本プロジェクトでは、`TodoOperationTelemetryAspect` により以下の Todo 業務操作だけを低カーディナリティ span と metrics の対象にします。

| 対象メソッド | operation |
| --- | --- |
| `TodoServiceImpl.listTodos` | `todo.list` |
| `TodoServiceImpl.getTodo` | `todo.get` |
| `TodoServiceImpl.createTodo` | `todo.create` |
| `TodoServiceImpl.updateTodo` | `todo.update` |
| `TodoServiceImpl.deleteTodo` | `todo.delete` |

手動 span は OpenTelemetry API を使い、Java Agent が設定する `GlobalOpenTelemetry` を参照します。アプリ内で別 SDK / exporter は起動しません。

`BusinessMetricsService` の `todo.operation.count` / `todo.operation.duration` は Micrometer API のまま維持します。Datadog への送信は Java Agent の Micrometer instrumentation を第一候補とし、prod canary 相当の deploy 後に Datadog Metrics API で到達を確認済みです。

## ログ相関

- app logs は SLF4J / Logback JSON -> stdout -> FireLens -> Datadog Logs の経路を維持する。
- OTLP logs は使わない。
- Datadog Logs / APM 相関では `trace_id` / `span_id` を主キーとする。
- `trace_id` は 32 文字小文字 hex、`span_id` は 16 文字小文字 hex の OpenTelemetry `SpanContext` 由来とする。
- `X-Amzn-Trace-Id` は `x_amzn_trace_id` として補助情報に限定する。
- `traceId` / `spanId` の旧キーは JSON ログトップレベルへ復活させない。

## 確認事項

- 確認済み: prod canary 相当の deploy 後、Datadog Logs で `trace_id` / `span_id` が確認できる。
- 確認済み: `todo.operation.count` / `todo.operation.duration` は Java Agent Micrometer instrumentation 経由で Datadog Metrics に到達する。
- 確認済み: JDBC metrics、Runtime Metrics、HikariCP metrics は Datadog Metrics API で確認できる。確認済みの metric 名は `docs/infra/o11y.md` を参照する。
- 確認済み: Java Agent 導入後の ECS task は steady state に到達し、OOM kill / restart / essential container stop は発生していない。
- 確認事項: prod で異常が出た場合の rollback 判断者は、運用担当者を別途明示する。

## 関連ドキュメント

- [docs 全体入口](../docs/README.md)
- [backend ドキュメント入口](../docs/backend/README.md)
- [backend ログ / 手動業務テレメトリ設計](../docs/backend/logging.md)
- [Observability 仕様](../docs/infra/o11y.md)
- [backend 開発手順](../docs/development/backend-development.md)
- [ADR-0004: APMエージェントの選定](../docs/adr/adr-0004-OTel-Java-Agent.md)
