# backend 開発手順

## この手順の目的

- `backend/` のローカル開発で使う基本コマンドを統一する。
- AWS 実行時との差分（特に O11y 設定）を明確にする。

## 前提

- Java 21
- Maven（`backend/` には Maven Wrapper を置いていないため `mvn` を使う）
- ローカル起動時に利用する PostgreSQL 互換 DB（任意）

## 作業ディレクトリ

```bash
cd backend
```

## 基本コマンド

```bash
mvn test
mvn -DskipTests compile
mvn spring-boot:run
```

## 起動確認

```bash
curl -i http://localhost:8080/actuator/health
```

- `/actuator/health` は認証不要。
- `/api/todos` は JWT 認証必須（Authorization 未指定時は `401`）。

## 環境変数

### DB 接続

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_HOST`
- `SPRING_DATASOURCE_PORT`
- `SPRING_DATASOURCE_DBNAME`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

### JWT issuer

- `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`

### O11y（Datadog / OpenTelemetry / Micrometer）

- `DD_SERVICE`（固定: `todo-backend`）
- `DD_ENV`（`dev` / `stg` / `prod`）
- `DD_VERSION`（`DockerImageAsset.imageTag` 由来）
- `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`（既定: `http://localhost:4317`）
- `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT`（既定: `grpc`）
- `MANAGEMENT_OTLP_METRICS_EXPORT_URL`（既定: `http://localhost:4318/v1/metrics`）
- `OTEL_SERVICE_NAME`
- `OTEL_RESOURCE_ATTRIBUTES`
- `OTEL_EXPORTER_OTLP_ENDPOINT`
- `OTEL_EXPORTER_OTLP_PROTOCOL`
- `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`
- `OTEL_TRACES_EXPORTER`
- `OTEL_METRICS_EXPORTER`
- `OTEL_LOGS_EXPORTER`

## O11y 方針（実装準拠）

- logs: `SLF4J + Logback(JSON)` を stdout へ出力し、ECS では FireLens が Datadog Logs へ転送する。
- metrics: `BusinessMetricsService` は Micrometer API を使い、`todo.operation.count` / `todo.operation.duration` を送信する。
- trace: OTel tracer（AOP 計装）で span を作成し、OTLP/gRPC（4317）で送信する。
- `trace_id` / `span_id` がログ相関の主キーで、`x_amzn_trace_id` は補助キー。

## テスト環境と実行環境の差分

### テスト（`src/test/resources/application.properties`）

- H2（PostgreSQL 互換モード）
- Flyway 無効（`spring.flyway.enabled=false`）
- `ddl-auto=create-drop`

### 実行環境（`src/main/resources/application.properties`）

- PostgreSQL
- Flyway 有効
- `ddl-auto=validate`

## AWS 実行時の注意

- ECS 実行時は Secrets Manager から `SPRING_DATASOURCE_*` が注入される。
- 公開経路は `CloudFront -> ALB -> ECS`。
- JWT 検証は Cognito issuer を前提にするため、issuer 不一致時は `401` が増加しログインループの原因になる。
- Datadog 側確認手順は `docs/infra/o11y.md` を参照する。

## 関連

- [backend 入口 README](../../backend/README.md)
- [backend ログ設計](../backend/logging.md)
- [AWS デプロイ手順（Monorepo 全体）](./aws-deployment-manual.md)
- [O11y 仕様（infra）](../infra/o11y.md)
