# OpenTelemetry Java Agent 対応 概要設計

## 1. 位置づけ

本書は、`backend/` の Spring Boot アプリケーションへ OpenTelemetry Java Agent（`opentelemetry-javaagent.jar`）を導入するための概要設計書である。
Spec-Driven Development（SDD）の後続工程で、仕様書、実装計画、タスク分解、検証観点を作成するための前提を整理する。

本書は、次の資料と現行実装を照合して作成した。

- `docs/adr/adr-0004-OTel-Java-Agent.md`
- `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`
- `docs/adr/adr-0002-trace-correlation-otel-datadog.md`
- `docs/adr/adr-0003-OTel-DatadogAgent-settings.md`
- `docs/infra/o11y.md`
- `docs/backend/logging.md`
- `backend/README.md`
- `backend/pom.xml`
- `backend/src/main/resources/application.properties`
- `backend/src/main/java/com/example/backend/telemetry/`
- `infra/lib/constructs/todo-backend-ecs-service-construct.ts`

## 2. 背景

現行構成では、APM 向けの trace/span と metrics は主に Spring Boot OpenTelemetry、Micrometer、OpenTelemetry API、独自 AOP による手動計装で構成されている。
現行の主な実装は次のとおりである。

| 領域 | 現行実装 |
| --- | --- |
| Trace / Span | `spring-boot-starter-opentelemetry`、`PublicMethodTelemetryAspect`、OpenTelemetry `Tracer` API |
| 業務 metrics | `BusinessMetricsService` が Micrometer `MeterRegistry` へ `todo.operation.count` / `todo.operation.duration` を記録 |
| Logs / APM 相関 | `trace_id` / `span_id` を主キー、`x_amzn_trace_id` を補助キーとして扱う |
| Logs 経路 | app stdout -> FireLens -> Datadog Logs |
| Trace 経路 | app -> OTLP/gRPC `4317` -> Datadog Agent sidecar -> Datadog APM |
| Metrics 経路 | app -> OTLP/HTTP `4318` -> Datadog Agent sidecar -> Datadog Metrics |
| Datadog タグ | `DD_SERVICE=todo-backend`、`DD_ENV=<dev|stg|prod>`、`DD_VERSION=<imageTag>` |

しかし、次の要件は手動計装だけで継続的に対応することが難しい。

- Trace / Span に JDBC instrumentation を追加する。
- Metrics に JDBC instrumentation を追加する。
- Metrics に Runtime Metrics を追加する。

ADR-0004 では、自動計装エージェントとして OpenTelemetry Java Agent を採用し、OTLP で Datadog Agent の OTLP ingest エンドポイントへ送信する方針が Accepted として決定されている。
そのため、今後は OpenTelemetry Java Agent を trace/span と自動 metrics 生成の主役に移行する。

## 3. 目的

OpenTelemetry Java Agent 対応の目的は次のとおりである。

- JDBC query span をアプリコードへの個別 span 実装なしに取得する。
- JDBC / DB client metrics を自動計装で取得する。
- JVM Runtime Metrics を自動計装で取得する。
- Trace / Span の生成主体を Spring Boot OTel / 独自 AOP 中心から Java Agent 中心へ移行する。
- 既存の Datadog Agent sidecar、FireLens、Unified Service Tagging、ログ相関方針を維持する。
- 業務 metrics など、自動計装で取得できないアプリ固有 telemetry の扱いを明確にする。

## 4. ADR-0004 の決定内容との整合

本設計は ADR-0004 の次の決定に従う。

| ADR-0004 の決定 | 本設計での扱い |
| --- | --- |
| OpenTelemetry Java Agent を採用する | `dd-java-agent.jar` は採用せず、`opentelemetry-javaagent.jar` を JVM 起動時に attach する |
| OTLP で Datadog Agent OTLP ingest へ送信する | 既存 sidecar の `4317` / `4318` を継続利用する |
| Datadog Java Tracer ではなくベンダー非依存を優先する | Datadog 独自 API 前提の計装は追加しない |
| Datadog 固有機能は一部制約がある | DBM / Continuous Profiler 等は本設計の対象外とし、必要時に ADR 再評価対象とする |
| Datadog 互換性に重大問題があれば再評価する | 受け入れ条件に Datadog 上での trace / metrics / logs 確認を含める |

## 5. スコープ

本設計の対象範囲は次のとおりである。

| 対象 | 内容 |
| --- | --- |
| backend | Java Agent 同梱、JVM 起動設定、依存関係整理、既存手動計装の見直し |
| infra | ECS タスク定義の app コンテナ環境変数、起動オプション、Datadog Agent sidecar 設定の見直し |
| docs | `backend/README.md`、`docs/infra/o11y.md`、`docs/backend/logging.md` の更新要否確認 |
| Datadog 運用 | Java Agent 導入後の Logs / APM / Metrics 受入確認観点 |

本設計で対象とする signal は次のとおりである。

- Logs
- Trace / Span
- Metrics
- JDBC instrumentation
- Runtime Metrics

## 6. 対象外

次の内容は本設計の対象外とする。

- 公開 API 契約の変更。
- DB スキーマ変更。
- 認証・認可仕様の変更。
- Datadog Java Tracer（`dd-java-agent.jar`）の導入。
- OpenTelemetry Logs の OTLP 送信への切り替え。
- Datadog DBM、Continuous Profiler、App and API Protection の詳細設計。
- Datadog ダッシュボード、Monitor、Index / Exclusion Filter の詳細設計。
- 本番向け Datadog Agent バージョン固定の最終決定。

## 7. 現行 Signal 別構成

| Signal | 生成元 | アプリ内の主役 | ECS 内の経路 | Datadog 側の到達先 | 現行の注意点 |
| --- | --- | --- | --- | --- | --- |
| Logs | SLF4J / Logback | 構造化ログ、MDC、`trace_id` / `span_id` | app stdout -> FireLens | Datadog Logs | OTLP logs は使わない |
| Trace / Span | Spring Boot OTel、OpenTelemetry API、独自 AOP | OpenTelemetry SDK / exporter、`PublicMethodTelemetryAspect` | app -> OTLP/gRPC `4317` -> Datadog Agent | Datadog APM | DB/JPA/JDBC span は現行要件を満たす範囲では見えていない |
| Metrics | Micrometer `MeterRegistry` | `BusinessMetricsService`、Actuator / Micrometer | app -> OTLP/HTTP `4318` -> Datadog Agent | Datadog Metrics | 業務 metrics は Micrometer API に統一されている |
| OTLP 送信 | アプリ JVM | Spring Boot OTel exporter / Micrometer OTLP Registry | traces: `4317`、metrics: `4318` | Datadog Agent sidecar | logs は OTLP 送信しない |
| OTLP 受信/転送 | Datadog Agent sidecar | Datadog Agent | `4317` traces、`4318` metrics | Datadog APM / Metrics | Datadog Agent は span / metrics を生成する役ではない |

## 8. Java Agent 導入後の Signal 別構成

| Signal | 生成元 | アプリ内の主役 | ECS 内の経路 | Datadog 側の到達先 | 設計上の注意点 |
| --- | --- | --- | --- | --- | --- |
| Logs | SLF4J / Logback、Java Agent の Logback / MDC instrumentation または既存 MDC 実装 | 構造化ログ、MDC、`trace_id` / `span_id` | app stdout -> FireLens | Datadog Logs | OTLP logs は使わない。`OTEL_LOGS_EXPORTER=none` を維持する |
| Trace / Span（自動計装） | `opentelemetry-javaagent.jar` | Java Agent 内蔵 OpenTelemetry SDK / OTLP exporter | app -> OTLP/gRPC `4317` -> Datadog Agent | Datadog APM | Servlet / Spring Web MVC / JDBC などの framework / library span を自動生成する |
| Trace / Span（業務補助） | 必要に応じて OpenTelemetry API、`@WithSpan`、method instrumentation、限定 AOP | Java Agent と OpenTelemetry API | app -> OTLP/gRPC `4317` -> Datadog Agent | Datadog APM | Java Agent は任意の業務メソッドをすべて自動 span 化するものではない |
| Metrics（自動計装） | `opentelemetry-javaagent.jar` | Java Agent 内蔵 MeterProvider / OTLP metric exporter | app -> OTLP/HTTP `4318` -> Datadog Agent | Datadog Metrics | JDBC metrics は semantic convention stability opt-in が必要。Runtime Metrics は Java automatic instrumentation で利用する |
| Metrics（業務） | `BusinessMetricsService` または OTel Metrics API へ移行した業務計装 | Micrometer API または OpenTelemetry Metrics API | app -> OTLP/HTTP `4318` -> Datadog Agent | Datadog Metrics | 業務 metrics は自動生成されない。既存 Micrometer metrics の送信方式を確定する必要がある |
| OTLP 送信 | アプリ JVM + `opentelemetry-javaagent.jar` | Java Agent 内蔵 OTLP exporter | traces: `4317`、metrics: `4318` | Datadog Agent sidecar | Java Agent 2.x は既定 protocol が `http/protobuf` のため、signal 別 endpoint / protocol を明示する |
| OTLP 受信/転送 | Datadog Agent sidecar | Datadog Agent OTLP receiver | `4317` traces、`4318` metrics | Datadog APM / Metrics | 現行 sidecar 構成を継続利用する |

## 9. 対象となる自動計装範囲

### 9.1 Trace / Span

Java Agent により自動計装する trace/span の主な対象は次のとおりである。

| 対象 | 期待する telemetry | 備考 |
| --- | --- | --- |
| Servlet / HTTP server | inbound HTTP request span | `/api/todos` のリクエスト単位の trace を取得する |
| Spring Web MVC | controller span / route 情報 | `http.route` などの route 単位集計を期待する |
| JDBC | database client span | JPA / Hibernate の裏で実行される SQL が主に JDBC span として見える想定 |
| HTTP client | outbound HTTP client span | 現行 Todo API で外部 HTTP 呼び出しがない場合は実データなし |

注意点:

- Java Agent は Repository / Service などアプリ固有メソッドをすべて自動 span 化するものではない。
- 現行の `PublicMethodTelemetryAspect` をそのまま残すと、Java Agent の Controller span と手動 public method span が重複し、trace が読みにくくなる可能性がある。
- 業務処理単位の span が必要な場合は、全 public method ではなく、必要な低カーディナリティ操作に限定して手動計装する。

### 9.2 JDBC instrumentation

Java Agent で JDBC instrumentation を有効にし、DB client span と DB client metrics を取得する。
現行 backend は Spring Data JPA / Hibernate / PostgreSQL JDBC driver を使用しているため、アプリコードから見た DB 操作は Repository / JPA 経由だが、APM 上では主に JDBC query span として観測する。

設計上の注意点:

- SQL 文は sanitizer を有効にし、値や bind parameter を telemetry に出さない。
- `db.statement` または相当属性へ機密値が出ないことを受け入れ確認に含める。
- SQL 文全文、JWT、Secrets Manager 由来の接続情報、個人情報の生値は telemetry に含めない。
- JDBC metrics は OpenTelemetry の semantic convention stability opt-in が必要なため、実装時に `OTEL_SEMCONV_STABILITY_OPT_IN` の設定値を確定する。

### 9.3 Metrics

Java Agent により自動計装する metrics の主な対象は次のとおりである。

| 対象 | 期待する metrics | 備考 |
| --- | --- | --- |
| HTTP server | request duration / count 等 | Spring Web MVC / Servlet 自動計装由来 |
| JDBC / database client | `db.client.operation.duration` 等 | semantic convention stability opt-in が必要 |
| HikariCP / DB pool | database pool metrics | Spring Boot の DataSource 実装と agent 対応状況を検証する |
| JVM Runtime Metrics | heap、GC、thread 等 | Datadog 側の runtime metrics 表示を確認する |
| Micrometer business metrics | `todo.operation.count` / `todo.operation.duration` | 自動生成ではない。既存実装の送信方式を別途確定する |

現行の業務 metrics は `BusinessMetricsService` が Micrometer `MeterRegistry` に記録している。
Java Agent 導入後も `todo.operation.count` / `todo.operation.duration` を維持する場合、次のいずれかを選択する必要がある。

| 選択肢 | 内容 | 評価 |
| --- | --- | --- |
| A. Micrometer API を残し、Java Agent の Micrometer instrumentation で export する | `BusinessMetricsService` は維持し、Java Agent 側で Micrometer meters を OTel metrics として収集する | 既存業務コード変更が少ない。Micrometer instrumentation は default disabled のため明示有効化が必要 |
| B. `BusinessMetricsService` を OpenTelemetry Metrics API へ移行する | アプリ固有 metrics を OTel API で明示的に記録する | Java Agent の OTel SDK と整合しやすいが、業務 metrics 実装変更が必要 |
| C. Micrometer OTLP Registry を限定的に残す | 業務 metrics の送信だけ Micrometer OTLP Registry を継続する | 自動 metrics と二重送信・タグ不整合のリスクがあるため原則非推奨 |

本設計では、第一候補を「A. Micrometer API を残し、Java Agent の Micrometer instrumentation で export する」とする。
ただし、実装前に Java Agent の採用バージョンで `todo.operation.*` が期待どおり Datadog Metrics に届くことを PoC で確認する。

### 9.4 Runtime Metrics

Runtime Metrics は Java Agent の Java Platform / runtime telemetry により取得する。
Datadog 側では OpenTelemetry runtime metrics が Datadog runtime metrics へマッピングされるため、次を確認対象とする。

- JVM heap / non-heap memory
- GC
- thread
- class loading
- CPU / process 関連 metrics（Datadog 側で見える範囲）

注意点:

- metrics 名や mapping は Java Agent と Datadog Agent のバージョンに依存する可能性がある。
- Datadog 上で runtime metrics dashboard / service page に表示されることを受け入れ確認に含める。
- runtime metrics と既存 Micrometer JVM metrics が重複する場合は、どちらを正とするかを実装時に整理する。

## 10. 既存の手動計装との関係

### 10.1 維持する方針

次の既存方針は Java Agent 導入後も維持する。

- アプリログは SLF4J / Logback で stdout へ JSON 出力する。
- アプリログは FireLens 経由で Datadog Logs へ送る。
- OTLP logs は使わず、`OTEL_LOGS_EXPORTER=none` を維持する。
- Datadog Logs / APM 相関の主キーは `trace_id` / `span_id` とする。
- `X-Amzn-Trace-Id` は `x_amzn_trace_id` として補助的に扱う。
- `DD_SERVICE` / `DD_ENV` / `DD_VERSION` と `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES` の整合を維持する。
- `DD_VERSION` / `service.version` は `BackendImageDeploymentConstruct.imageTag` 由来値を単一ソースとする。
- 業務ログや telemetry にシークレット、認証情報、トークン本文、個人情報の生値を含めない。

### 10.2 見直す方針

次の既存実装は Java Agent 導入後に見直す。

| 現行実装 | 見直し理由 | 方針 |
| --- | --- | --- |
| `spring-boot-starter-opentelemetry` | Java Agent が SDK / exporter / 自動計装の主役になるため | Java Agent 導入後は削除候補。手動 API が必要な場合は `opentelemetry-api` 等の最小依存にする |
| `PublicMethodTelemetryAspect` の span 作成 | Java Agent の Controller / HTTP span と重複しやすい | 全 public method span は原則廃止または限定化する |
| `PublicMethodTelemetryAspect` の metrics 記録 | 業務 metrics 維持に必要だが span 責務と結合している | span 責務と metrics 責務を分離するか、対象 operation を限定する |
| `OpenTelemetryLogbackAppenderInitializer` | Java Agent の Logback / MDC instrumentation と重複する可能性がある | Java Agent で `trace_id` / `span_id` が出ることを確認し、不要なら削除する |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_*` | Spring Boot OTel exporter 用設定 | Java Agent 主経路では削除候補 |
| `MANAGEMENT_OTLP_METRICS_EXPORT_URL` | Micrometer OTLP Registry 用設定 | Java Agent metrics exporter へ寄せる場合は削除候補 |

## 11. 変更する点

### 11.1 backend

backend 側の主な変更候補は次のとおりである。

| ファイル / 領域 | 変更内容 |
| --- | --- |
| `Dockerfile` | `opentelemetry-javaagent.jar` を実行イメージへ配置する |
| JVM 起動設定 | `-javaagent:/app/opentelemetry-javaagent.jar` を付与する。`JAVA_TOOL_OPTIONS` 利用も候補とする |
| `pom.xml` | `spring-boot-starter-opentelemetry`、`opentelemetry-logback-appender-1.0`、AOP 関連依存の要否を見直す |
| `application.properties` | Spring Boot OTel exporter 前提の設定を Java Agent 前提へ整理する |
| `PublicMethodTelemetryAspect` | 全 public method span の継続要否を判断し、削除・限定化・責務分離する |
| `BusinessMetricsService` | Micrometer API 維持または OTel Metrics API への移行方針を確定する |
| `OpenTelemetryLogbackAppenderInitializer` | Java Agent の MDC / Logback instrumentation で代替できるか検証し、削除候補とする |
| backend tests | Java Agent 導入後のログ相関、metrics、不要 span 削減に合わせてテストを更新する |

### 11.2 infra

infra 側の主な変更候補は次のとおりである。

| ファイル / 領域 | 変更内容 |
| --- | --- |
| `todo-backend-ecs-service-construct.ts` | app コンテナへ Java Agent 用起動オプション / 環境変数を追加する |
| app コンテナ環境変数 | Java Agent の signal 別 OTLP endpoint / protocol を明示する |
| app コンテナ環境変数 | JDBC metrics の semantic convention stability opt-in を追加する |
| app コンテナ環境変数 | Micrometer business metrics を維持する場合は Micrometer instrumentation を明示有効化する |
| Datadog Agent sidecar | `4317` gRPC / `4318` HTTP の OTLP receiver を継続する |
| Datadog Agent image | `latest` 運用のままでよいか、OTLP ingest 対応バージョンを固定するか確認する |

## 12. 削除する点

実装時に削除候補となるものは次のとおりである。

| 削除候補 | 削除条件 |
| --- | --- |
| `spring-boot-starter-opentelemetry` | Java Agent が trace / metrics exporter と自動計装の主経路になり、Spring Boot starter が不要になった場合 |
| `opentelemetry-logback-appender-1.0` | Java Agent の MDC / Logback instrumentation だけで `trace_id` / `span_id` を JSON ログへ出せることを確認した場合 |
| `OpenTelemetryLogbackAppenderInitializer` | 上記 appender 依存を削除する場合 |
| `PublicMethodTelemetryAspect` の span 作成 | Java Agent の自動計装へ移行し、全 public method span が不要と判断された場合 |
| `spring.aop.proxy-target-class=true` | AOP による手動 span / metrics 記録を削除し、他用途がない場合 |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | Spring Boot OTel trace exporter を使わない場合 |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT` | Spring Boot OTel trace exporter を使わない場合 |
| `MANAGEMENT_OTLP_METRICS_EXPORT_URL` | Micrometer OTLP Registry を使わない場合 |

削除時の注意点:

- `BusinessMetricsService` を維持する場合、`MeterRegistry` の bean 供給と Datadog への export 経路が失われないことを確認する。
- `OpenTelemetryLogbackAppenderInitializer` を削除する場合、ログのトップレベルに `trace_id` / `span_id` が引き続き出ることをテストで固定する。
- AOP 関連依存を削除する場合、業務 metrics の記録タイミングが失われないことを確認する。

## 13. 引き続き手動計装が必要な範囲

Java Agent 導入後も、次の範囲は自動計装だけでは満たせない。

| 範囲 | 理由 | 方針 |
| --- | --- | --- |
| 業務 metrics | `todo.operation.count` / `todo.operation.duration` はアプリ固有の意味を持つため自動生成されない | Micrometer API 維持または OTel Metrics API へ移行する |
| 業務 span | `todo.create` などの業務単位 span は Java Agent だけでは保証されない | 必要な operation だけ `@WithSpan`、method instrumentation、または限定 AOP で実装する |
| 監査ログ | 業務上の監査対象と主体ハッシュは自動計装対象外 | 既存 Controller / Service の SLF4J ログを維持する |
| `ownerSubjectHash` | PII 生値を避けた監査相関はアプリ固有 | `OwnerSubjectHashService` を維持する |
| Datadog remapper 確認 | `trace_id` が Datadog 予約属性として扱われるかは Datadog 側設定に依存する | Datadog Logs / APM で受け入れ確認する |

## 14. 設定方針

Java Agent 導入後は、Spring Boot OTel exporter 用設定ではなく、OpenTelemetry Java Agent 用設定を中心にする。

### 14.1 app コンテナ環境変数

| 環境変数 | 方針 | 備考 |
| --- | --- | --- |
| `JAVA_TOOL_OPTIONS` | `-javaagent:/app/opentelemetry-javaagent.jar` を設定する候補 | ENTRYPOINT 直接指定でもよい。どちらか一方に統一する |
| `OTEL_SERVICE_NAME` | `todo-backend` | `DD_SERVICE` と一致させる |
| `OTEL_RESOURCE_ATTRIBUTES` | `service.name=todo-backend,service.version=<imageTag>,deployment.environment=<env>` | `DD_VERSION` / `DD_ENV` と一致させる |
| `OTEL_TRACES_EXPORTER` | `otlp` | trace exporter 有効化 |
| `OTEL_METRICS_EXPORTER` | `otlp` | metrics exporter 有効化 |
| `OTEL_LOGS_EXPORTER` | `none` | OTLP logs は使わない |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4317` | trace は Datadog Agent OTLP/gRPC へ送る |
| `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL` | `grpc` | Java Agent 2.x の既定 protocol に依存しない |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `http://localhost:4318/v1/metrics` | metrics は Datadog Agent OTLP/HTTP へ送る |
| `OTEL_EXPORTER_OTLP_METRICS_PROTOCOL` | `http/protobuf` | `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` の影響を受けないよう signal 別に明示する |
| `OTEL_SEMCONV_STABILITY_OPT_IN` | `database` または `database/dup` | JDBC metrics の取得方針として実装時に確定する |
| `OTEL_INSTRUMENTATION_MICROMETER_ENABLED` | `true` 候補 | 既存 Micrometer 業務 metrics を Java Agent で export する場合に必要 |
| `OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED` | `true` | SQL 値の露出防止のため明示する候補。既定値も有効だが、運用方針として無効化しない |
| `DD_SERVICE` | `todo-backend` | Unified Service Tagging |
| `DD_ENV` | `dev` / `stg` / `prod` | `TelemetryEnvironmentValidator` の許容値と一致 |
| `DD_VERSION` | `<imageTag>` | `BackendImageDeploymentConstruct.imageTag` 由来 |

注意点:

- 現行 infra では `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317` と `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` が global に設定されている。
- Java Agent で metrics を `4318` HTTP へ送る場合、global protocol が metrics に誤適用されないよう、signal 別 protocol を明示する。
- `MANAGEMENT_*` は Spring Boot exporter 用設定であり、Java Agent 主経路では不要になる可能性が高い。

### 14.2 Datadog Agent sidecar

Datadog Agent sidecar は現行の OTLP receiver 設定を維持する。

| 環境変数 | 現行値 | 方針 |
| --- | --- | --- |
| `DD_APM_ENABLED` | `true` | 維持 |
| `DD_APM_NON_LOCAL_TRAFFIC` | `true` | 維持 |
| `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT` | `0.0.0.0:4317` | trace 受信で維持 |
| `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT` | `0.0.0.0:4318` | metrics 受信で維持 |
| `DD_SERVICE` | `todo-backend` | 維持 |
| `DD_ENV` | `<env>` | 維持 |
| `DD_VERSION` | `<imageTag>` | 維持 |
| `DD_TAGS` | `team:o11y-CoE,system:todo,aws_account:<accountId>` | 維持。`service` / `env` / `version` は重複定義しない |

## 15. ログ相関方針

ログ相関は ADR-0002 の方針を維持する。

- `trace_id` は OpenTelemetry `SpanContext.traceId` 由来の 32 文字小文字 hex とする。
- `span_id` は OpenTelemetry `SpanContext.spanId` 由来の 16 文字小文字 hex とする。
- `RequestLoggingContextFilter` は `trace_id` / `span_id` を独自生成・上書きしない。
- `X-Amzn-Trace-Id` は `x_amzn_trace_id` として補助的に保持する。
- `traceId` / `spanId` のような曖昧な旧キーは JSON ログのトップレベルから除外する。
- Datadog で `trace_id` が自動認識されない場合は、Datadog 側の Preprocessing / Trace ID remapper を設定する。

Java Agent 導入後は、`trace_id` / `span_id` をログへ出す仕組みを次の順で検証する。

1. Java Agent の Logback / MDC instrumentation で既存 JSON ログに `trace_id` / `span_id` が出るか確認する。
2. 出ない場合は、既存 `opentelemetry-logback-appender-1.0` または最小の MDC 補助実装を残す。
3. いずれの場合も、OTLP logs は送信しない。

## 16. 設計上の制約

- OpenTelemetry Java Agent と Datadog Java Tracer を同一 JVM に同時 attach しない。
- Java Agent と Spring Boot OTel starter の SDK / exporter を二重運用しない。
- Java Agent による自動 metrics と Micrometer OTLP Registry による metrics を無条件に併用しない。
- 業務 metrics のタグは低カーディナリティに限定する。
- SQL、JWT、Authorization header、Cookie、Secrets Manager 由来値、個人情報生値を telemetry に含めない。
- DB schema / API 契約 / 認証認可仕様は変更しない。
- app ログの主経路は FireLens のままとし、CloudWatch Logs へ app ログを直接二重送信しない。
- `DD_SERVICE` / `DD_ENV` / `DD_VERSION` を `DD_TAGS` に重複定義しない。
- Datadog Agent の OTLP receiver は app と同一 ECS task 内の sidecar として利用する。

## 17. 実装時の注意点

- Java Agent のバージョンを決め、Docker image へどの方法で配置するかを固定する。
- `opentelemetry-javaagent.jar` の取得元、バージョン、checksum 検証有無を実装計画で明記する。
- `JAVA_TOOL_OPTIONS` を使う場合、既存 ENTRYPOINT と二重指定しない。
- Java Agent の起動ログは stderr に出るため、FireLens 経由で Datadog Logs に入る可能性がある。ログ量が過剰な場合は `OTEL_JAVAAGENT_LOGGING` を調整する。
- `OTEL_JAVAAGENT_DEBUG=true` は verbose なため、本番で常時有効にしない。
- JDBC instrumentation で SQL sanitizer を無効化しない。
- Runtime Metrics と Micrometer JVM metrics が重複する場合は、Datadog 上の採用 metric を整理する。
- `PublicMethodTelemetryAspect` を削除または限定化する場合、`todo.operation.*` metrics が維持されているかを必ず確認する。
- `BusinessMetricsService` を OTel Metrics API に移行する場合、`opentelemetry-api` 依存を compile scope で持つ必要がある。
- Datadog 側の metric 名変換、runtime metrics mapping、database metrics 名は Agent / OTel バージョン差分の影響を受ける可能性がある。

## 18. 受け入れ条件

Java Agent 対応の受け入れ条件は次のとおりである。

### 18.1 backend / local

- backend が Java 21 で起動できる。
- `opentelemetry-javaagent.jar` が JVM に attach されていることを起動ログまたは telemetry で確認できる。
- 既存 API `/api/todos` の契約が変わらない。
- backend の関連テストが通る。
- JSON ログに `trace_id` / `span_id` が 32/16 文字小文字 hex で出力される。
- `x_amzn_trace_id` は補助情報としてのみ出力される。
- `traceId` / `spanId` の旧キーが新規ログ仕様で復活していない。

### 18.2 Datadog APM

- `/api/todos` へのリクエストが Datadog APM の trace として表示される。
- HTTP server / Spring Web MVC の span が確認できる。
- Todo の DB 操作に対して JDBC query span が確認できる。
- `service:todo-backend`、`env:<env>`、`version:<imageTag>` で絞り込める。
- Trace から関連 Logs へ遷移できる。
- Logs から該当 Trace へ遷移できる。

### 18.3 Datadog Metrics

- JDBC / database client metrics が確認できる。
- JVM Runtime Metrics が確認できる。
- 既存業務 metrics `todo.operation.count` / `todo.operation.duration` を維持する方針を採った場合、それらが引き続き確認できる。
- metrics のタグに `service` / `env` / `version` が整合している。
- 高カーディナリティ値や機密値が metrics attributes に含まれていない。

### 18.4 infra

- Datadog Agent sidecar の `4317` / `4318` receiver が起動している。
- app コンテナから `localhost:4317` / `localhost:4318` へ送信できる。
- `DatadogAgentContainer` の CloudWatch Logs に OTLP 受信・転送エラーが継続的に出ていない。
- `LogRouterContainer` の CloudWatch Logs に FireLens 転送エラーが継続的に出ていない。

## 19. ドキュメント更新方針

実装時には次のドキュメント更新要否を確認する。

| ドキュメント | 更新要否 |
| --- | --- |
| `backend/README.md` | O11y 環境変数、Java Agent 起動、Micrometer の扱いが変わるため更新が必要 |
| `docs/infra/o11y.md` | Signal 別経路が Spring Boot OTel / Micrometer OTLP Registry から Java Agent 中心へ変わるため更新が必要 |
| `docs/backend/logging.md` | `trace_id` / `span_id` の注入方式が変わる場合は更新が必要 |
| `infra/README.md` | ECS タスクの O11y 環境変数や trace / metrics 経路説明が変わる場合は更新が必要 |
| `docs/adr/adr-0004-OTel-Java-Agent.md` | ADR 番号表記、Related docs、Agent version 制約を補正する場合は更新を検討する |
| `docs/adr/adr-0003-OTel-DatadogAgent-settings.md` | Java Agent 導入後に Micrometer OTLP Registry を主経路から外す場合は、Superseded / 更新を検討する |

## 20. 未確定事項 / 確認事項

実装前に、以下の事項を確認または決定する。

### 20.1 バージョンと取得方法

- OpenTelemetry Java Agent は `latest` を使用せず、明示バージョンに固定する。
    - 現時点の候補: `2.28.1`
    - 採用前に release note を確認し、breaking changes や設定変更の有無を確認する。
- `opentelemetry-javaagent.jar` は Docker build 時に取得し、runtime 起動時にはダウンロードしない。
    - 取得元、checksum 検証有無、Docker cache 方針を実装計画に記載する。
- Datadog Agent image は `latest` を避け、OTLP traces / metrics ingest を検証済みの 7.x 具体バージョンへ pin する。
- ADR-0004 に記載された Datadog Agent の OTLP ingest 対応バージョンは、実装時点の Datadog 公式 docs と照合する。

### 20.2 Java Agent 起動方式

- Java Agent の attach 方法は `JAVA_TOOL_OPTIONS` を第一候補とする。
- ENTRYPOINT / CMD と `JAVA_TOOL_OPTIONS` の両方に `-javaagent` を設定しない。
- 既存の `JAVA_TOOL_OPTIONS` がある場合は上書きせず、既存値を保持して追記する。

### 20.3 Metrics 方針

- JDBC metrics の semantic convention stability opt-in は、最終構成では `OTEL_SEMCONV_STABILITY_OPT_IN=database` を第一候補とする。
- 移行確認で旧 experimental metrics との比較が必要な場合のみ、一時的に `database/dup` を使用する。
- 既存 business metrics `todo.operation.*` は自動計装では生成されないため、第一候補として Micrometer API を維持する。
- Micrometer API を維持する場合は `OTEL_INSTRUMENTATION_MICROMETER_ENABLED=true` を設定し、Datadog Metrics に `todo.operation.*` が届くことを受け入れ条件に含める。
- Runtime Metrics は Java Agent 由来を第一候補とし、既存 Micrometer JVM metrics と重複する場合は採用する metric 系列を整理する。

### 20.4 手動計装とログ相関

- `PublicMethodTelemetryAspect` の全 public method span 作成は原則廃止または限定化する。
- 業務 metrics が Aspect に依存している場合は、span 作成責務と metrics 記録責務を分離する。
- `trace_id` / `span_id` のログ出力は Java Agent の Logback MDC instrumentation を第一候補とする。
- Java Agent MDC だけで既存 JSON ログのトップレベルに `trace_id` / `span_id` が出ない場合のみ、既存補助実装を最小限残す。
- `opentelemetry-logback-appender-1.0` と Java Agent Logback MDC instrumentation は、最終構成では併用しない。
- Datadog 上で Logs / APM 相関が機能しない場合は、Datadog Logs pipeline の Trace ID remapper または Preprocessing 設定要否を確認する。

## 21. 参照情報

### 21.1 リポジトリ内資料

- `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`
- `docs/adr/adr-0002-trace-correlation-otel-datadog.md`
- `docs/adr/adr-0003-OTel-DatadogAgent-settings.md`
- `docs/adr/adr-0004-OTel-Java-Agent.md`
- `docs/infra/o11y.md`
- `docs/backend/logging.md`
- `specs/003-OTel-to-backend/specs.md`
- `specs/003-OTel-to-backend-fix-02/specs.md`

### 21.2 外部一次情報

- OpenTelemetry Java Agent: https://opentelemetry.io/docs/zero-code/java/agent/
- OpenTelemetry Java Agent Configuration: https://opentelemetry.io/docs/zero-code/java/agent/configuration/
- OpenTelemetry Java Agent Supported Libraries: https://opentelemetry.io/docs/zero-code/java/agent/supported-libraries/
- OpenTelemetry Java Agent API extension: https://opentelemetry.io/docs/zero-code/java/agent/api/
- OpenTelemetry Database Semantic Conventions: https://opentelemetry.io/docs/specs/semconv/db/
- Datadog OTLP Ingestion by the Datadog Agent: https://docs.datadoghq.com/opentelemetry/setup/otlp_ingest_in_the_agent/
- Datadog OpenTelemetry Runtime Metrics: https://docs.datadoghq.com/opentelemetry/integrations/runtime_metrics/?tab=java
