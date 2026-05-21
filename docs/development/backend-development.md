# backend 開発手順

## この手順の目的

- `backend/` のローカル開発で必要な基本コマンドをまとめる
- 実行環境（AWS）との差分を明確にする

## 前提

- Java 21
- Maven Wrapper（`backend/mvnw`）
- ローカル起動時に利用する PostgreSQL 互換 DB（任意）

## 作業ディレクトリ

```bash
cd backend
```

## 基本コマンド

```bash
./mvnw test
./mvnw -DskipTests compile
./mvnw spring-boot:run
```

## 起動確認

```bash
curl -i http://localhost:8080/actuator/health
```

- `/actuator/health` は認証不要です。
- `/api/todos` は JWT 認証必須です（Authorization 未指定時は `401`）。

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

- ECS 実行時は Secrets Manager から `SPRING_DATASOURCE_*` が注入されます。
- 公開経路は `CloudFront -> ALB -> ECS` です。
- JWT 検証は Cognito issuer を前提にしています。

## 関連

- [backend 入口 README](../../backend/README.md)
- [backend ログ設計](../backend/logging.md)
- [AWS デプロイ手順（Monorepo 全体）](./aws-deployment-manual.md)
