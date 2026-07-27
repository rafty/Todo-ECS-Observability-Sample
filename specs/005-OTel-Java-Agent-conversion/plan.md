# Plan: OpenTelemetry Java Agent conversion

## 実装方針

本 feature は `backend/`、`infra/`、`docs/` にまたがる複数領域の変更として実施する。目的は、Trace / Span と自動計装 metrics の主経路を OpenTelemetry Java Agent に移行しつつ、既存の Datadog Agent sidecar、FireLens ログ経路、`trace_id` / `span_id` 相関、Unified Service Tagging、業務 metrics を維持することである。

実装は一括置換ではなく、次の順序で段階的に進める。

1. Java Agent のバージョン、Maven artifact copy による取得方式、Datadog Agent image pin、OTLP signal 別設定を確定する。
2. `backend/Dockerfile` に Java Agent を同梱し、ECS では `JAVA_TOOL_OPTIONS` で JVM に attach する。
3. `infra/` で app コンテナの OTel Java Agent 用環境変数、Datadog Agent image pin、`/actuator/health` 除外設定を反映する。
4. Java Agent 自動計装で HTTP / Spring Web MVC / JDBC span、JDBC metrics、Runtime Metrics を送信する。
5. Trace / Span（業務/手動計装）と業務 metrics を、全 public method AOP ではなく低カーディナリティ operation に限定した形へ移行する。
6. `spring-boot-starter-opentelemetry`、Micrometer OTLP Registry、`opentelemetry-logback-appender-1.0`、AOP 依存の削除可否を検証後に判断する。
7. Datadog 上で Logs / APM / Metrics の相関、取り込み量、runtime metrics、JDBC telemetry、業務 metrics を確認する。
8. README / docs / ADR を実装結果に合わせて更新する。

AWS / ECS Fargate の観点では、既存の同一 task 内 sidecar 構成を維持する。app から Datadog Agent sidecar への通信は `localhost` を使う前提とし、app ログは引き続き FireLens で Datadog Logs へ送る。CloudWatch Logs は `DatadogAgentContainer` と `LogRouterContainer` の診断ログ用途に限定する。

## 変更対象

### backend

| 対象                                                                                                 | 変更方針                                                                                                                                                    |
| -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `backend/Dockerfile`                                                                               | Maven artifact copy により `opentelemetry-javaagent.jar` を build stage で取得し、実行イメージへ `/app/opentelemetry-javaagent.jar` として同梱する。runtime 起動時 download は行わない  |
| `backend/pom.xml`                                                                                  | `spring-boot-starter-opentelemetry`、`opentelemetry-logback-appender-1.0`、AOP 関連依存の継続要否を段階的に整理する。`opentelemetry-javaagent.jar` は通常依存として `pom.xml` に追加しない |
| `backend/src/main/resources/application.properties`                                                | Spring Boot OTel exporter 前提の `otel.*` 設定を Java Agent / 環境変数中心へ整理する                                                                                     |
| `backend/src/main/java/com/example/backend/telemetry/PublicMethodTelemetryAspect.java`             | 全 public method span 作成を廃止または限定化し、span 作成責務と metrics 記録責務を分離する                                                                                          |
| `backend/src/main/java/com/example/backend/telemetry/BusinessMetricsService.java`                  | `todo.operation.count` / `todo.operation.duration` を維持する。第一候補は Micrometer API 維持                                                                        |
| `backend/src/main/java/com/example/backend/telemetry/OpenTelemetryLogbackAppenderInitializer.java` | Java Agent の Logback MDC instrumentation で要件を満たせる場合は削除候補にする                                                                                             |
| `backend/src/main/java/com/example/backend/logging/RequestLoggingContextFilter.java`               | `trace_id` / `span_id` は生成せず、`x_amzn_trace_id` 補助方針を維持する。必要時のみテスト調整                                                                                     |
| `backend/src/test/`                                                                                | AOP 責務分離、業務 metrics、ログ相関、環境 validator のテストを更新する                                                                                                         |

### infra

| 対象                                                           | 変更方針                                                                                        |
| ------------------------------------------------------------ | ------------------------------------------------------------------------------------------- |
| `infra/lib/constructs/todo-backend-ecs-service-construct.ts` | `TodoBackendContainer` に Java Agent attach と signal 別 OTLP 環境変数を追加する                        |
| `infra/lib/constructs/todo-backend-ecs-service-construct.ts` | Spring Boot OTel / Micrometer OTLP Registry 用の `MANAGEMENT_*` 変数を削除候補として整理する                |
| `infra/lib/constructs/todo-backend-ecs-service-construct.ts` | `DatadogAgentContainer` の image を `latest` から検証済み具体バージョンへ pin する                            |
| `infra/lib/constructs/todo-backend-ecs-service-construct.ts` | `DatadogAgentContainer` に `/actuator/health` 由来 trace を除外する `DD_APM_IGNORE_RESOURCES` を追加する |
| `infra/lib/config/environment-config.ts`                     | Datadog Agent image tag、APM TPS、sidecar resource を設定値として管理するか検討する                           |
| ECS task definition                                          | 現行 task CPU `1024` / memory `2048 MiB` と sidecar resource の妥当性を検証する                         |
| FireLens 設定                                                  | app ログの FireLens 経路を維持する。Fargate で `s3` custom config は導入しない                                |

### docs / ADR

| 対象                                                | 変更方針                                                                                                               |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `backend/README.md`                               | Java Agent 導入後の主要 O11y 環境変数、起動前提、手動業務 span 方針を反映する                                                                 |
| `infra/README.md`                                 | ECS task の app / Datadog Agent / FireLens 構成、Datadog Agent image pin、signal 別 OTLP 経路、`/actuator/health` 除外方針を反映する |
| `docs/infra/o11y.md`                              | Signal 別経路を Java Agent 中心へ更新する                                                                                     |
| `docs/backend/logging.md`                         | `trace_id` / `span_id` 注入方式が変わる場合に更新する                                                                             |
| `docs/adr/adr-0003-OTel-DatadogAgent-settings.md` | Spring Boot OTel / Micrometer OTLP Registry 前提の更新または supersede を判断する                                               |
| `docs/adr/adr-0004-OTel-Java-Agent.md`            | Datadog Agent OTLP ingest minimum version、関連 docs、reviewer / owner の更新要否を確認する                                      |

### Datadog 運用確認

* APM: HTTP / Spring Web MVC / JDBC span、Trace / Span（業務/手動計装）、Logs 相関を確認する。
* APM: `/actuator/health` 由来 trace が `DD_APM_IGNORE_RESOURCES` により継続的に表示されないことを確認する。
* Metrics: JDBC metrics、HikariCP / database pool metrics、Runtime Metrics、`todo.operation.*` を確認する。
* Metrics: `/actuator/health` 由来 HTTP server metrics は初期実装では生成を許容する。ただし API SLO、latency、throughput、error rate の dashboard / monitor では除外する。
* Logs: `trace_id` / `span_id`、`x_amzn_trace_id`、`service` / `env` / `version` を確認する。
* Datadog Agent diagnostics: `DatadogAgentContainer` の CloudWatch Logs で OTLP 受信・転送エラーを確認する。
* FireLens diagnostics: `LogRouterContainer` の CloudWatch Logs でログ転送エラーを確認する。

## 変更しないもの

* 公開 API `/api/todos` の request / response 契約は変更しない。
* DB schema、Flyway migration、JPA entity の永続化契約は変更しない。
* Cognito / JWT 検証、`owner_subject` 境界、認可仕様は変更しない。
* Datadog Java Tracer（`dd-java-agent.jar`）は導入しない。
* OTLP logs は導入しない。`OTEL_LOGS_EXPORTER=none` を維持する。
* app ログを CloudWatch Logs へ直接送る構成には戻さない。
* FireLens による Datadog Logs 経路は維持する。
* `DD_SERVICE=todo-backend`、`DD_ENV=<dev|stg|prod>`、`DD_VERSION=<imageTag>` の Unified Service Tagging 方針は維持する。
* `trace_id` / `span_id` を主相関キー、`x_amzn_trace_id` を補助情報とする方針は維持する。
* `/actuator/health` の公開仕様、ALB / ECS health check 用途は変更しない。
* Datadog Logs pipeline の Trace ID remapper / Preprocessing は初期実装では追加しない。
* Datadog DBM、Continuous Profiler、App and API Protection、dashboard / monitor / index / exclusion filter の詳細設計はこの計画では扱わない。
* frontend / load-test の機能変更は行わない。ただし検証で負荷をかける場合、既存 load-test 手順を参照する可能性はある。

## 技術方針

### Java Agent の同梱と attach

* Java Agent jar の取得方式は Maven artifact copy に決定する。
* Java Agent は `backend/Dockerfile` の build stage で Maven artifact として取得し、final image へ `/app/opentelemetry-javaagent.jar` として配置する。
* 取得する Maven artifact は `io.opentelemetry.javaagent:opentelemetry-javaagent:<version>` とする。
* GitHub Releases などからの直接 URL download は採用しない。
* runtime 起動時 download は行わない。
* 社内 artifact cache がある場合は、Dockerfile に社内 URL を直書きせず、Maven mirror / Maven settings 経由で透過的に利用する。
* `opentelemetry-javaagent.jar` はアプリケーションの通常依存として `pom.xml` に追加しない。
* `opentelemetry-javaagent.jar` の version は `latest` ではなく明示値で固定する。
* 実行ユーザー `spring` が agent jar を読み取れる owner / permission にする。
* JVM attach は `JAVA_TOOL_OPTIONS=-javaagent:/app/opentelemetry-javaagent.jar` に統一する。
* `ENTRYPOINT` へ直接 `-javaagent` を追加しない。
* 既存の `JAVA_TOOL_OPTIONS` がある場合は上書きせず、既存値を保持して `-javaagent:/app/opentelemetry-javaagent.jar` を追加する。

Dockerfile の実装例は次の方針とする。

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS otel-agent

ARG OTEL_JAVA_AGENT_VERSION=2.28.1

RUN mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy \
    -Dartifact=io.opentelemetry.javaagent:opentelemetry-javaagent:${OTEL_JAVA_AGENT_VERSION}:jar \
    -DoutputDirectory=/otel-agent \
    -Dmdep.stripVersion=true
```

final image 側では次のように配置する。

```dockerfile
COPY --from=otel-agent --chown=spring:spring \
    /otel-agent/opentelemetry-javaagent.jar \
    /app/opentelemetry-javaagent.jar
```

### OTLP signal 別設定

Java Agent 2.x の既定 protocol や既存 `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` の影響を避けるため、signal 別 endpoint / protocol を明示する。

| Signal              | 予定設定                                                                                                         |
| ------------------- | ------------------------------------------------------------------------------------------------------------ |
| traces              | `OTEL_TRACES_EXPORTER=otlp`                                                                                  |
| traces endpoint     | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4317`                                                   |
| traces protocol     | `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=grpc`                                                                    |
| metrics             | `OTEL_METRICS_EXPORTER=otlp`                                                                                 |
| metrics endpoint    | `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=http://localhost:4318/v1/metrics`                                       |
| metrics protocol    | `OTEL_EXPORTER_OTLP_METRICS_PROTOCOL=http/protobuf`                                                          |
| metrics temporality | `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=delta` を第一候補として検証する                                       |
| logs                | `OTEL_LOGS_EXPORTER=none`                                                                                    |
| service             | `OTEL_SERVICE_NAME=todo-backend`                                                                             |
| resource            | `OTEL_RESOURCE_ATTRIBUTES=service.name=todo-backend,service.version=<imageTag>,deployment.environment=<env>` |

既存の generic `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_PROTOCOL` は、signal 別設定と矛盾しない形に整理する。Spring Boot starter / Micrometer OTLP Registry を削除する段階では、`MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_*` と `MANAGEMENT_OTLP_METRICS_EXPORT_URL` は削除候補とする。

### Trace / Span（自動計装）

* Java Agent により HTTP server / Servlet / Spring Web MVC / JDBC span を取得する。
* `/api/todos` の inbound request span と Spring route 情報を確認する。
* JPA / Hibernate 経由の DB 操作は JDBC query span として確認する。
* `/actuator/health` 由来 span は、APM 上のノイズと取り込み量増加を避けるため、Datadog Agent sidecar の `DD_APM_IGNORE_RESOURCES` で除外する。
* `/actuator/health` 由来 trace は Datadog APM の trace ingestion および trace metrics の対象外にする。
* `/actuator/health` 由来 HTTP server metrics は初期実装では生成自体を止めない。
* API SLO、latency、throughput、error rate の dashboard / monitor では `/actuator/health` を除外する。
* `DD_APM_IGNORE_RESOURCES` の regex は canary deploy 後に Datadog APM の root span resource 名を確認して調整する。
* `/actuator/health` の公開仕様や ALB / ECS health check 用途は変更しない。

### Trace / Span（業務/手動計装）

* Java Agent は任意の業務メソッドをすべて span 化しないため、Trace / Span（業務/手動計装）は独立した計画対象として扱う。
* 現行 `PublicMethodTelemetryAspect` の全 public method span は、Java Agent 自動計装と重複しやすいためそのまま継続しない。
* 第一候補は、対象 operation を `todo.list`、`todo.get`、`todo.create`、`todo.update`、`todo.delete` など低カーディナリティな Todo 業務操作に限定した手動計装へ置き換えること。
* 実装方式は次の順で検討する。

| 候補                   | 方針                                                                        | 評価                                |
| -------------------- | ------------------------------------------------------------------------- | --------------------------------- |
| 限定 AOP               | pointcut を Todo controller / service の必要 method に限定し、span と metrics を制御する | 既存実装との差分が小さい。AOP 依存は残る            |
| `@WithSpan`          | 対象 method に annotation を付与し、Java Agent の annotation instrumentation に寄せる  | span は明確になるが、metrics timing は別途必要 |
| 明示 OpenTelemetry API | service 層で明示的に span を作成する                                                 | 依存と責務が明確だが、業務コードへの混入が増える          |

* 業務 span の属性は `business.operation`、`result.status` など低カーディナリティ値に限定する。
* owner subject、JWT、Authorization header、DB 接続情報、SQL bind parameter、PII 生値は span attributes に含めない。
* 手動業務 span は Java Agent の SDK / exporter を利用し、別 SDK / exporter を起動しない。

### 業務 metrics

* `todo.operation.count` / `todo.operation.duration` は維持する。
* 第一候補は `BusinessMetricsService` の Micrometer API を維持し、Java Agent の Micrometer instrumentation で export すること。
* `OTEL_INSTRUMENTATION_MICROMETER_ENABLED=true` を設定し、Datadog Metrics に `todo.operation.*` が届くか PoC で確認する。
* Micrometer bridge で期待どおり export できない場合のみ、OpenTelemetry Metrics API への移行を検討する。
* Micrometer OTLP Registry と Java Agent metrics exporter の二重送信は避ける。
* `service` / `env` / `operation` / `result` 以外の高カーディナリティタグを追加しない。

### JDBC metrics / semantic conventions

* `OTEL_SEMCONV_STABILITY_OPT_IN=database` を第一候補とする。
* 比較検証が必要な場合のみ、一時的に `database/dup` を使う。
* SQL sanitizer は無効化しない。必要であれば `OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED=true` を明示する。
* Datadog 上で `db.client.operation.duration` 相当の metric、HikariCP / DB pool metrics、PostgreSQL JDBC span を確認する。

### Runtime Metrics

* Java Agent の runtime telemetry を第一候補とする。
* 既存 Micrometer JVM metrics と Datadog 上で重複する場合、Java Agent 由来を正とするか、Micrometer 側を残すかを検証結果で判断する。
* Datadog の OTLP metrics ingest では Delta temporality が推奨されるため、`OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=delta` を第一候補とする。
* Java Agent runtime metrics と既存 Micrometer metrics の重複、Datadog mapping への影響は canary で確認する。
* Runtime metric 名は Datadog mapping を壊す可能性があるため rename しない。

### ログ相関

* `RequestLoggingContextFilter` は引き続き `requestId`、`path`、`httpMethod`、`x_amzn_trace_id` を扱う。
* `trace_id` / `span_id` は OpenTelemetry `SpanContext` 由来とし、独自生成しない。
* Java Agent の Logback MDC instrumentation を第一候補として、JSON ログトップレベルに `trace_id` / `span_id` が出るか確認する。
* Java Agent だけで既存 JSON ログ要件を満たせない場合に限り、`opentelemetry-logback-appender-1.0` または最小補助実装を残す。
* Datadog Logs pipeline の Trace ID remapper / Preprocessing は初期実装では追加しない。
* JSON ログのトップレベルに OpenTelemetry 標準の `trace_id` / `span_id` を出力する方針を維持する。
* `service` / `env` / `version` は Unified Service Tagging と整合させる。
* Datadog 上で Logs -> Trace、Trace -> Logs の双方向遷移が成立することを受け入れ条件にする。
* 双方向遷移が成立しない場合のみ、Datadog Logs pipeline の Preprocessing / Trace ID remapper の追加を検討する。
* その場合も、アプリ側のログキー名 `trace_id` / `span_id` は変更しない。
* Datadog 側の設定不足を理由に、アプリ側で `dd.trace_id` / `dd.span_id` へ安易に変更しない。

### Datadog Logs pipeline 方針

* 初期実装では、Datadog Logs pipeline の Trace ID remapper / Preprocessing は追加しない。
* アプリログは JSON として送信し、トップレベルに `trace_id` / `span_id` を出力する。
* Datadog が `trace_id` / `span_id` を自動認識しない場合のみ、Datadog 側の Preprocessing / Trace ID remapper を追加する。
* Datadog 側の設定不足を理由に、アプリ側のログキー名を変更しない。

### `/actuator/health` telemetry 方針

* `/actuator/health` 由来 telemetry の扱いは次の方針に決定する。

  * APM span は Datadog Agent sidecar の `DD_APM_IGNORE_RESOURCES` で除外する。
  * `/actuator/health` は Datadog APM の trace ingestion および trace metrics の対象外にする。
  * HTTP server metrics は初期実装では生成自体を止めない。
  * API SLO、latency、throughput、error rate の dashboard / monitor では `/actuator/health` を除外する。
  * Datadog APM 上の root span resource 名を canary で確認し、`DD_APM_IGNORE_RESOURCES` の regex を必要に応じて調整する。
* `DD_APM_IGNORE_RESOURCES` の候補値は次のとおりとする。

```text
^GET /actuator/health(/.*)?$,^HEAD /actuator/health(/.*)?$
```

* canary で root span resource 名が上記と異なる場合は、業務 API を誤って除外しない範囲で regex を調整する。

### 依存関係整理

* Java Agent 導入直後は、機能欠損を避けるため依存削除を一度に行わない。
* Java Agent で traces / metrics / logs correlation / business metrics が成立することを確認後、次の順で削除可否を判断する。

  1. `spring-boot-starter-opentelemetry`
  2. `opentelemetry-logback-appender-1.0`
  3. `OpenTelemetryLogbackAppenderInitializer`
  4. `spring-aop` / `aspectjweaver` / `spring.aop.proxy-target-class=true`
* AOP は Trace / Span（業務/手動計装）または業務 metrics timing に必要な場合のみ限定的に残す。

### ECS / Fargate / Datadog Agent

* Datadog Agent image は `public.ecr.aws/datadog/agent:latest` から、OTLP traces / metrics ingest を検証済みの具体バージョンへ pin する。
* image tag は construct 直書きではなく、`DatadogConfig` へ設定値として持たせることを検討する。
* `DatadogAgentContainer` に `/actuator/health` 由来 trace を除外するための `DD_APM_IGNORE_RESOURCES` を追加する。
* `DD_APM_IGNORE_RESOURCES` の候補値は `^GET /actuator/health(/.*)?$,^HEAD /actuator/health(/.*)?$` とし、canary で Datadog APM の root span resource 名を確認して調整する。
* 現行の task CPU `1024` / memory `2048 MiB`、Datadog Agent `256 CPU / 512 MiB`、LogRouter `64 CPU / 128 MiB` で Java Agent 導入後も安定稼働するか確認する。
* OOM kill、CPU throttling、startup time 増加、Datadog Agent の OTLP receiver error を確認する。
* `DD_APM_MAX_TPS=2` は JDBC span 増加後に trace 欠落の原因になり得るため、canary / load test 後に調整要否を判断する。
* Fargate では FireLens custom config の `s3` 利用に制約があるため、本 feature では custom config を新規導入しない。

## データや契約への影響

### DB スキーマ

* 変更なし。
* Flyway migration は追加しない。
* JPA entity / repository の永続化契約は変更しない。

### API 契約

* `/api/todos` の request / response、HTTP status、認証認可仕様は変更しない。
* `/actuator/health` の公開仕様は維持する。
* `/actuator/health` の telemetry 取り扱いを変更しても、health check API の response 契約は変更しない。

### イベント契約

* アプリケーションイベントや外部イベント契約は現行リポジトリ上で対象なし。

### 環境変数

| 区分                        | 方針                                                                                                                                                                                                                                                                |
| ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 追加                        | `JAVA_TOOL_OPTIONS`、`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`、`OTEL_EXPORTER_OTLP_TRACES_PROTOCOL`、`OTEL_EXPORTER_OTLP_METRICS_PROTOCOL`、`OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE`、`OTEL_SEMCONV_STABILITY_OPT_IN`、`OTEL_INSTRUMENTATION_MICROMETER_ENABLED` |
| Datadog Agent sidecar に追加 | `DD_APM_IGNORE_RESOURCES`                                                                                                                                                                                                                                         |
| 維持・明示                     | `OTEL_TRACES_EXPORTER`、`OTEL_METRICS_EXPORTER`、`OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`、`OTEL_LOGS_EXPORTER=none`、`OTEL_SERVICE_NAME`、`OTEL_RESOURCE_ATTRIBUTES`、`DD_SERVICE`、`DD_ENV`、`DD_VERSION`                                                                  |
| 削除候補                      | `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`、`MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT`、`MANAGEMENT_OTLP_METRICS_EXPORT_URL`                                                                                                             |
| 整理候補                      | generic `OTEL_EXPORTER_OTLP_ENDPOINT`、`OTEL_EXPORTER_OTLP_PROTOCOL`                                                                                                                                                                                               |

### Secret / 設定値

* 新規 secret は不要の想定。
* `DD_API_KEY` は引き続き Secrets Manager から Datadog Agent sidecar と FireLens secret option へ渡す。
* DB 接続情報は引き続き Secrets Manager から `SPRING_DATASOURCE_*` へ渡す。
* Java Agent version、Datadog Agent image tag、Maven repository / mirror 設定、`DD_APM_IGNORE_RESOURCES` は secret ではなく build / config 値として扱う。

### デプロイや運用への影響

* backend image に Java Agent jar が入るため image size が増える。
* JVM 起動時に Java Agent が attach されるため startup time、CPU、memory が変化する可能性がある。
* JDBC span / metrics と Runtime Metrics が増えるため、Datadog の取り込み量と可視化粒度が変化する。
* `/actuator/health` 由来 trace は Datadog Agent sidecar で除外するため、APM 上のノイズと trace 取り込み量を抑制できる。
* `/actuator/health` 由来 HTTP server metrics は初期実装では生成を許容するため、API SLO / dashboard / monitor 側で除外条件を設ける。
* Datadog Agent image pin により、意図しない最新化は防げるが、以後の更新は明示的な version update が必要になる。
* Spring Boot starter / Micrometer OTLP Registry を削除する場合、従来の management 系環境変数は無効になるため README / docs の更新が必要になる。

## リスク

| リスク                                                    | 影響                                           | 対策                                                                                                  |
| ------------------------------------------------------ | -------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| Java Agent attach 失敗                                   | traces / metrics が欠損する                       | 起動ログ、container smoke test、ECS task 起動後の Datadog Agent diagnostics を確認する                             |
| Maven artifact copy 失敗                                 | Docker build が失敗し、Java Agent を image に同梱できない | Maven artifact coordinates、version、Maven repository / mirror 設定を確認する                                |
| signal 別 OTLP 設定ミス                                     | traces は届くが metrics が届かない、またはその逆が起きる         | traces / metrics endpoint と protocol を signal 別に明示し、Datadog Agent receiver logs を確認する               |
| Spring Boot OTel starter と Java Agent の二重 exporter     | telemetry 重複、コスト増、相関不整合                      | Java Agent 成立後に Spring Boot OTel exporter と management env を削除する                                    |
| `PublicMethodTelemetryAspect` の単純削除                    | `todo.operation.*` または業務 span が失われる          | span 作成責務と metrics 記録責務を先に分離し、テストで確認する                                                              |
| 全 public method span の継続                               | trace 可読性低下、Datadog 取り込み量増加                  | Todo 業務 operation に限定する                                                                             |
| Micrometer instrumentation が期待どおり動かない                  | `todo.operation.*` が Datadog に届かない           | PoC で確認し、必要なら OTel Metrics API へ移行する                                                                |
| Java Agent Logback MDC だけで `trace_id` / `span_id` が出ない | Logs / APM 相関が壊れる                            | 既存 appender または最小補助実装を fallback とする                                                                 |
| Datadog が `trace_id` / `span_id` を自動認識しない              | Logs から Trace、Trace から Logs への遷移ができない        | 初期実装では remapper を追加せず、検証で相関できない場合のみ Datadog Logs pipeline の Preprocessing / Trace ID remapper を追加する |
| `/actuator/health` 由来 trace の除外漏れ                      | APM ノイズ、trace ingestion 増加、trace metrics の歪み | `DD_APM_IGNORE_RESOURCES` を設定し、Datadog APM 上で継続的に表示されないことを確認する                                      |
| `DD_APM_IGNORE_RESOURCES` の regex が広すぎる                | 業務 API の trace が誤って除外される                     | canary で root span resource 名を確認し、`/api/todos` など業務 API が除外されていないことを確認する                            |
| JDBC SQL 属性に機密値が出る                                     | セキュリティ事故                                     | SQL sanitizer を無効化しない。受け入れ確認で属性を確認する                                                                |
| Runtime Metrics と Micrometer JVM metrics の重複           | metrics ノイズ、コスト増                             | Datadog 上の系列を確認し、採用系列を決める                                                                           |
| `DD_APM_MAX_TPS=2` が低すぎる                               | JDBC span 追加後に trace 欠落が増える                  | canary / load test 後に調整要否を判断する                                                                      |
| ECS task resource 不足                                   | OOM kill、CPU throttling、起動失敗                 | task / container metrics を確認し、必要時のみ Fargate size を調整する                                              |
| Datadog Agent image pin の選定ミス                          | OTLP ingest 非対応または不具合                        | 公式 docs と canary で receiver 起動・受信を確認する                                                              |
| FireLens custom config の誤用                             | Fargate task 起動失敗                            | 本 feature では S3 custom config を導入しない                                                                |

## 検証方針

### backend local

* `./mvnw test` を実行する。
* `./mvnw -DskipTests compile` を実行し、依存整理後の compile を確認する。
* `BusinessMetricsServiceTest` で `todo.operation.count` / `todo.operation.duration` の継続を確認する。
* AOP / 手動業務 span を変更した場合、対象 operation と低カーディナリティ属性のテストを更新する。
* ログ相関テストで `trace_id` が 32 文字小文字 hex、`span_id` が 16 文字小文字 hex、`traceId` / `spanId` が復活していないことを確認する。

### backend container

* `docker build` または CDK synth 時の Docker build で Maven artifact copy により Java Agent jar が image に含まれることを確認する。
* `/app/opentelemetry-javaagent.jar` が image に含まれることを確認する。
* 実行ユーザーが `/app/opentelemetry-javaagent.jar` を読み取れることを確認する。
* container 起動時に `JAVA_TOOL_OPTIONS` による `-javaagent:/app/opentelemetry-javaagent.jar` が有効であることを起動ログまたは Java Agent log で確認する。
* `/actuator/health` が従来どおり応答することを確認する。
* app コンテナから `localhost:4317` / `localhost:4318` へ到達できることを ECS 上で確認する。

### infra / CDK

* `infra/` で `npm ci` を必要に応じて実行する。
* `npm run build` を実行する。
* `npm test -- --runInBand` を実行する。
* `npx cdk synth -c env=prod` を実行する。
* 影響確認が必要な場合は `npx cdk diff -c env=prod` を実行し、task definition、container env、Datadog Agent image pin、`DD_APM_IGNORE_RESOURCES`、resource 変更だけに差分が限定されているか確認する。

### ECS / AWS runtime

* dev または stg 相当で canary deploy を行い、task が安定起動することを確認する。
* `DatadogAgentContainer` の CloudWatch Logs で OTLP receiver の起動と継続エラーなしを確認する。
* `LogRouterContainer` の CloudWatch Logs で FireLens 転送エラーなしを確認する。
* ECS task / container の CPU、memory、restart、OOM kill、startup time を確認する。
* `DD_APM_MAX_TPS` による trace 欠落がないか Datadog APM と Agent logs で確認する。
* `DD_APM_IGNORE_RESOURCES` により `/actuator/health` 由来 trace が除外されていることを確認する。
* `DD_APM_IGNORE_RESOURCES` により `/api/todos` など業務 API の trace が誤って除外されていないことを確認する。

### Datadog APM / Metrics / Logs

* APM で `/api/todos` の HTTP server / Spring Web MVC span を確認する。
* APM で Todo DB 操作に対する JDBC query span を確認する。
* APM で Trace / Span（業務/手動計装）の対象 operation span を確認する。
* APM に `/actuator/health` の trace が継続的に表示されないことを確認する。
* `/api/todos` など業務 API の trace は除外されていないことを確認する。
* `DD_APM_IGNORE_RESOURCES` により、意図しない resource が除外されていないことを確認する。
* Metrics で JDBC / database client metrics を確認する。
* Metrics で HikariCP / database pool metrics を確認する。
* Metrics で JVM Runtime Metrics を確認する。
* Metrics で `todo.operation.count` / `todo.operation.duration` を確認する。
* HTTP server metrics に `/actuator/health` が含まれる場合でも、API SLO / dashboard / monitor の集計では除外する。
* Logs で `service:todo-backend env:<env> version:<imageTag>` による検索を確認する。
* Logs で `trace_id` / `span_id` が出ることを確認する。
* Datadog Logs で `trace_id` / `span_id` が属性として認識されることを確認する。
* Trace から Logs、Logs から Trace への双方向遷移を確認する。
* 双方向遷移ができない場合のみ、Datadog Logs pipeline の Preprocessing / Trace ID remapper を追加する。
* SQL attributes、metrics attributes、log fields に機密値や高カーディナリティ値が混入していないことを確認する。

## ドキュメント更新方針

| ドキュメント                                            | 更新方針                                                                                                                                                   |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `backend/README.md`                               | O11y 環境変数、Java Agent attach、Maven artifact copy による Agent 同梱、Trace / Span（業務/手動計装）方針、Spring Boot starter 前提の変更を反映する                                    |
| `infra/README.md`                                 | ECS task の app / Datadog Agent / FireLens 構成、Datadog Agent image pin、signal 別 OTLP 経路、`DD_APM_IGNORE_RESOURCES` による `/actuator/health` trace 除外方針を反映する |
| `docs/infra/o11y.md`                              | 現行の Spring Boot OTel / Micrometer OTLP Registry 中心の記述を Java Agent 中心へ更新する                                                                              |
| `docs/backend/logging.md`                         | Java Agent Logback MDC または fallback 実装に合わせて `trace_id` / `span_id` 注入方式を更新する。Datadog Logs pipeline の remapper は初期実装では追加しない方針も記載する                      |
| `docs/adr/adr-0003-OTel-DatadogAgent-settings.md` | Java Agent 導入後に前提が変わるため、更新または supersede を判断する                                                                                                          |
| `docs/adr/adr-0004-OTel-Java-Agent.md`            | Datadog Agent OTLP ingest minimum version、関連 docs、reviewer / owner を更新するか確認する                                                                          |

ドキュメントは実装結果と検証結果に基づいて更新する。未検証の Datadog UI 表示や metric 名を断定しない。

## 実施順序

1. 事前確認を行う。

   * OpenTelemetry Java Agent version を確定する。
   * Datadog Agent image pin version を確定する。
   * Java Agent jar の取得方式は Maven artifact copy として進める。
   * Maven artifact coordinates、Maven repository / mirror 設定、Docker build stage の取得方法を確認する。
   * `JAVA_TOOL_OPTIONS` による attach を前提として、既存 `JAVA_TOOL_OPTIONS` の有無を確認する。
   * `OTEL_INSTRUMENTATION_MICROMETER_ENABLED=true` の PoC 方針を決める。
   * `/actuator/health` 除外用 `DD_APM_IGNORE_RESOURCES` の候補 regex を決める。

2. backend image に Java Agent を同梱する。

   * `Dockerfile` に Maven artifact copy による agent jar 取得・配置を追加する。
   * `/app/opentelemetry-javaagent.jar` が非 root 実行ユーザーから読めるようにする。
   * Docker build で image に jar が含まれることを確認する。
   * runtime 起動時 download が行われないことを確認する。

3. infra に Java Agent attach と signal 別 OTLP 設定を追加する。

   * `TodoBackendContainer` に `JAVA_TOOL_OPTIONS` を追加する。
   * trace / metrics の endpoint / protocol を signal 別に設定する。
   * `OTEL_LOGS_EXPORTER=none` を維持する。
   * `OTEL_SEMCONV_STABILITY_OPT_IN=database` と Micrometer instrumentation 設定を追加する。
   * `DatadogAgentContainer` に `/actuator/health` trace 除外用の `DD_APM_IGNORE_RESOURCES` を追加する。

4. Datadog Agent image を pin する。

   * `public.ecr.aws/datadog/agent:latest` を検証済み具体バージョンへ変更する。
   * 可能なら `environment-config.ts` に image tag / image name を持たせる。
   * `4317` / `4318` receiver 設定を維持する。
   * `DD_APM_IGNORE_RESOURCES` が Datadog Agent sidecar に設定されていることを確認する。

5. Java Agent 自動計装の受信確認を行う。

   * container 起動確認を行う。
   * Datadog APM で HTTP / Spring Web MVC / JDBC span を確認する。
   * Datadog Metrics で JDBC metrics と Runtime Metrics を確認する。
   * Datadog APM で `/actuator/health` 由来 trace が除外されていることを確認する。

6. 業務 metrics と Trace / Span（業務/手動計装）を整理する。

   * `PublicMethodTelemetryAspect` の span 作成責務と metrics 記録責務を分離する。
   * 全 public method span を廃止または限定化する。
   * Todo operation に限定した手動業務 span を残すか、`@WithSpan` / 限定 AOP / OpenTelemetry API のいずれかで実装する。
   * `todo.operation.*` が Java Agent 経由で Datadog Metrics に届くことを確認する。

7. ログ相関を確認し、fallback 要否を判断する。

   * Java Agent Logback MDC instrumentation で `trace_id` / `span_id` が JSON ログに出るか確認する。
   * Datadog Logs pipeline の Trace ID remapper / Preprocessing は初期実装では追加しない。
   * Datadog 上で Logs -> Trace、Trace -> Logs の双方向遷移を確認する。
   * 双方向遷移が成立しない場合のみ、Datadog Logs pipeline の Preprocessing / Trace ID remapper を追加する。
   * 要件を満たせる場合、`opentelemetry-logback-appender-1.0` と initializer を削除候補にする。
   * 要件を満たせない場合、最小補助実装または既存 appender 継続を判断する。

8. 依存関係と設定を削除・整理する。

   * Spring Boot OTel starter を削除できるか確認する。
   * Micrometer OTLP Registry 関連設定を削除できるか確認する。
   * AOP 依存を削除できるか確認する。
   * `application.properties` と ECS env の古い `MANAGEMENT_*` 設定を整理する。

9. AWS / Datadog 受け入れ確認を行う。

   * dev / stg で canary deploy する。
   * ECS task resource、Agent logs、FireLens logs、Datadog APM / Metrics / Logs を確認する。
   * `/actuator/health` 由来 trace が APM に継続表示されないことを確認する。
   * `/api/todos` など業務 API の trace が誤って除外されていないことを確認する。
   * API SLO / dashboard / monitor では `/actuator/health` を除外する。
   * `DD_APM_MAX_TPS` と task size の調整要否を判断する。

10. ドキュメントと ADR を更新する。

    * README / docs を実装結果に合わせて更新する。
    * ADR-0003 / ADR-0004 の更新要否を反映する。
    * 検証結果で確定した未解決事項を解消または残課題として明記する。

## 未解決事項

* OpenTelemetry Java Agent の採用バージョン。

  * `specs.md` では `2.28.1` が候補として記載されているが、実装時点の release note、Java 21、Spring Boot 4.0.5 互換性を確認して確定する。

* Datadog Agent image pin version。

  * `latest` は廃止するが、具体 version は Datadog OTLP ingest 対応条件と canary で確定する。

* ADR-0004 の Datadog Agent OTLP ingest minimum version 記載。

  * `specs.md` では ADR 記載と Datadog 現行 docs の差分が指摘されている。ADR 更新要否を確認する。

* `OTEL_INSTRUMENTATION_MICROMETER_ENABLED=true` で `todo.operation.*` が Datadog Metrics に届くか。

* Trace / Span（業務/手動計装）の実装方式。

  * 限定 AOP、`@WithSpan`、明示 OpenTelemetry API のどれを採用するか未確定。

* `PublicMethodTelemetryAspect` の最終扱い。

  * 削除、限定化、責務分離のどれにするか未確定。

* Java Agent Logback MDC instrumentation だけで既存 JSON ログ要件を満たせるか。

* Runtime Metrics と既存 Micrometer JVM metrics の重複有無。

* `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=delta` で Datadog runtime metrics mapping、JDBC metrics、業務 metrics が期待どおり表示されるか。

* 現行 ECS task CPU `1024` / memory `2048 MiB` と sidecar resource で Java Agent 導入後も安定稼働できるか。

* `DD_APM_MAX_TPS=2` が JDBC span 追加後も適切か。

* Datadog DBM、Continuous Profiler、App and API Protection が将来必須になった場合に ADR-0004 を再評価するか。

## 解決済み事項

* Java Agent jar の取得方式。

  * Maven artifact copy を採用する。
  * 直接 URL download は採用しない。
  * 社内 artifact cache がある場合は Maven mirror / Maven settings 経由で利用する。

* JVM 起動への attach 方法。

  * `JAVA_TOOL_OPTIONS=-javaagent:/app/opentelemetry-javaagent.jar` に統一する。
  * `ENTRYPOINT` への直接 `-javaagent` 追加は採用しない。

* Datadog Logs pipeline の Trace ID remapper / Preprocessing。

  * 初期実装では追加しない。
  * JSON ログトップレベルの `trace_id` / `span_id` を維持する。
  * Datadog 上で Logs / APM 相関できない場合のみ追加を検討する。

* `/actuator/health` 由来 telemetry。

  * APM span は Datadog Agent sidecar の `DD_APM_IGNORE_RESOURCES` で除外する。
  * HTTP server metrics は初期実装では生成を許容する。
  * API SLO、latency、throughput、error rate の dashboard / monitor では `/actuator/health` を除外する。

## 参考資料

* OpenTelemetry Java Agent configuration: [https://opentelemetry.io/docs/zero-code/java/agent/configuration/](https://opentelemetry.io/docs/zero-code/java/agent/configuration/)
* OpenTelemetry database semantic conventions: [https://opentelemetry.io/docs/specs/semconv/db/](https://opentelemetry.io/docs/specs/semconv/db/)
* Datadog OTLP Ingestion by the Datadog Agent: [https://docs.datadoghq.com/opentelemetry/interoperability/otlp_ingest_in_the_agent/](https://docs.datadoghq.com/opentelemetry/interoperability/otlp_ingest_in_the_agent/)
* Datadog OTLP Metrics Types: [https://docs.datadoghq.com/opentelemetry/reference/otlp_metric_types](https://docs.datadoghq.com/opentelemetry/reference/otlp_metric_types)
