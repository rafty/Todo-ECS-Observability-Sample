# backend

Spring Boot ベースの Todo API（認証付きバックエンド）です。  
AWS 実行時は `CloudFront -> ALB -> ECS -> Aurora` の経路で稼働し、DB 接続情報は Secrets Manager から注入されます。

## 主な責務

- `/api/todos` の CRUD API を提供する
- JWT（Cognito issuer）を検証し、`owner_subject` 境界でデータを分離する
- Flyway による `todos` スキーマ管理を行う
- ALB ヘルスチェック用に `/actuator/health` を公開する

## 前提

- Java 21
- Maven Wrapper（`./mvnw`）
- （ローカル起動時）PostgreSQL または互換環境
- Docker（コンテナビルド確認時）

## 主要コマンド

`backend/` 配下で実行します。

```bash
./mvnw test
./mvnw -DskipTests compile
./mvnw spring-boot:run
```

## 主要設定（環境変数）

- DB 接続
  - `SPRING_DATASOURCE_URL`
  - `SPRING_DATASOURCE_HOST`
  - `SPRING_DATASOURCE_PORT`
  - `SPRING_DATASOURCE_DBNAME`
  - `SPRING_DATASOURCE_USERNAME`
  - `SPRING_DATASOURCE_PASSWORD`
- JWT 検証
  - `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`
- O11y（Datadog / OpenTelemetry）
  - `DD_SERVICE`（固定: `todo-backend`）
  - `DD_ENV`（`dev` / `stg` / `prod`）
  - `DD_VERSION`（`infra/lib/constructs/backend-image-deployment-construct.ts` の `backendDockerImageAsset.imageTag` 由来）
  - `OTEL_SERVICE_NAME`
  - `OTEL_RESOURCE_ATTRIBUTES`
  - `OTEL_EXPORTER_OTLP_ENDPOINT`
  - `OTEL_EXPORTER_OTLP_PROTOCOL`
  - `OTEL_TRACES_EXPORTER`
  - `OTEL_METRICS_EXPORTER`
  - `OTEL_LOGS_EXPORTER`

補足:
- テスト実行時は `src/test/resources/application.properties` により H2 が利用されます。
- 本番相当（ECS）では Secrets Manager の値が `SPRING_DATASOURCE_*` に注入されます。
- Datadog Logs/APM 相関では `trace_id` / `span_id` を主キーとし、`X-Amzn-Trace-Id` は `x_amzn_trace_id` で補助的に扱います。
- 本サンプルではメソッド数が少ないため、Spring 管理 Bean の `public` メソッドを対象に Tracer API 併用で span を付与します。

## 関連ドキュメント

- [docs 全体入口](../docs/README.md)
- [backend ドキュメント入口](../docs/backend/README.md)
- [backend ログ設計](../docs/backend/logging.md)
- [backend 開発手順](../docs/development/backend-development.md)
