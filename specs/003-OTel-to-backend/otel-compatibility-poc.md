# OTel Compatibility PoC: 003-OTel-to-backend

## 目的

- Spring Boot `4.0.5` で OTel 導入方式（starter / Java Agent）を確定する。

## 実施内容

1. `pom.xml` に `spring-boot-starter-opentelemetry` を追加。
2. AOP 実装に必要な依存として `spring-aop` / `aspectjweaver` を追加。
3. `mvn -U -DskipTests compile` を実行し、依存解決とコンパイル工程を確認。

### Java Agent 方式フォールバック手順（作成のみ）

1. `opentelemetry-javaagent.jar` を実行環境へ配置する。
2. 起動引数 `-javaagent:/path/to/opentelemetry-javaagent.jar` を付与する。
3. `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES`, `OTEL_EXPORTER_OTLP_ENDPOINT` を環境変数で指定する。
4. Datadog Agent sidecar 側の OTLP/HTTP 受け口（`4318`）へ到達することを確認する。

## 結果

- `spring-boot-starter-opentelemetry` の依存解決は成功。
- ソースコンパイル工程（`maven-compiler-plugin`）まで進行。
- 現環境では JDK21 未導入のため、`release 21 is not supported` で停止。

## 判断

- 導入方式は **starter 方式を採用**する。
- フォールバックとして Java Agent 方式を候補に保持するが、本変更では starter 方式で実装を進める。

## 残課題

- JDK21 環境で `mvn test` まで完走し、起動時の OTel 自動計装（HTTP/DB）を最終確認する。
