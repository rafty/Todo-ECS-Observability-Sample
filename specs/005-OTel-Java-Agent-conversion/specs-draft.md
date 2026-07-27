# Spec Draft: OpenTelemetry Java Agent conversion

## 概要

`backend/` の Spring Boot アプリケーションに OpenTelemetry Java Agent（`opentelemetry-javaagent.jar`）を導入し、現行の Spring Boot OTel / Micrometer / 独自 AOP 中心の手動計装から、Java Agent を中心とした自動計装構成へ移行する。

本 feature では、以下を実現する。

- Trace / Span に JDBC instrumentation を追加する。
- Metrics に JDBC instrumentation を追加する。
- Metrics に JVM Runtime Metrics を追加する。
- Trace / Span と自動計装 metrics の生成主体を OpenTelemetry Java Agent に移行する。
- Java Agent では保証されない Trace / Span（業務/手動計装）を、必要な operation に限定して継続・見直しする。
- 既存の FireLens ログ経路、Datadog Agent sidecar、`trace_id` / `span_id` 主相関、Unified Service Tagging を維持する。
- 既存の業務 metrics（`todo.operation.count` / `todo.operation.duration`）を失わないように、Micrometer API 維持または OTel Metrics API 移行の方針を明確にする。

本 feature は `backend/` と `infra/` にまたがる変更を想定する。`docs/` は実装に合わせた更新対象とする。

## 背景

現行構成では、APM 向けの trace/span と metrics は主に以下の実装で構成されている。

| 領域 | 現行実装 |
| --- | --- |
| Trace / Span | `spring-boot-starter-opentelemetry`、OpenTelemetry API、`PublicMethodTelemetryAspect` |
| 業務 metrics | `BusinessMetricsService` が Micrometer `MeterRegistry` へ記録 |
| Logs / APM 相関 | `trace_id` / `span_id` を主キー、`x_amzn_trace_id` を補助キーとして扱う |
| Trace 経路 | app -> OTLP/gRPC `4317` -> Datadog Agent sidecar -> Datadog APM |
| Metrics 経路 | app -> OTLP/HTTP `4318` -> Datadog Agent sidecar -> Datadog Metrics |
| Logs 経路 | app stdout -> FireLens -> Datadog Logs |

この構成では、Controller / Service などアプリ固有処理の手動 span と業務 metrics は取得できているが、JDBC span、JDBC metrics、Runtime Metrics を継続的に手動実装するのは保守性が低い。

ADR-0004 では、Datadog Java Tracer ではなく OpenTelemetry Java Agent を採用することが Accepted として決定されている。採用理由は、ベンダーロックインの回避、OpenTelemetry 標準への整合、Datadog OTLP ingest との実用的な互換性である。

## 目的

- OpenTelemetry Java Agent により JDBC query span を自動取得できる状態にする。
- OpenTelemetry Java Agent により JDBC / database client metrics を自動取得できる状態にする。
- OpenTelemetry Java Agent により JVM Runtime Metrics を自動取得し、Datadog 上で確認できる状態にする。
- `spring-boot-starter-opentelemetry` / Micrometer OTLP Registry / 独自 AOP の役割を整理し、Java Agent との二重計装・二重 export を避ける。
- Java Agent が自動生成しない業務処理単位の span は、Trace / Span（業務/手動計装）として自動計装とは別に扱う。
- 既存の `/api/todos` API、DB schema、認証認可、構造化ログ、監査ログの仕様を変更しない。
- Datadog 上で `service:todo-backend env:<env> version:<imageTag>` を軸に Logs / Traces / Metrics を横断できる状態を維持する。

## スコープ

### 対象領域

| 領域 | 対象内容 |
| --- | --- |
| `backend/` | Java Agent jar の同梱、JVM attach、依存関係整理、OTel / Micrometer / AOP 関連実装の見直し、関連テスト更新 |
| `infra/` | ECS app コンテナの起動オプション、Java Agent 用環境変数、Datadog Agent sidecar の OTLP receiver 設定、Datadog Agent image pin 方針 |
| `docs/` | `backend/README.md`、`docs/infra/o11y.md`、`docs/backend/logging.md`、必要に応じた ADR 関連更新 |

### 対象 Signal

| Signal | 対応方針 |
| --- | --- |
| Logs | 既存の SLF4J / Logback -> stdout -> FireLens -> Datadog Logs を維持する |
| Trace / Span（自動計装） | Java Agent 自動計装を主経路とし、HTTP / Spring Web MVC / JDBC span を取得する |
| Trace / Span（業務/手動計装） | Java Agent が自動生成しない業務処理単位の span を、必要な operation に限定して OpenTelemetry API、`@WithSpan`、method instrumentation、限定 AOP のいずれかで維持または追加する |
| Metrics（自動計装） | Java Agent 自動計装を主経路とし、JDBC metrics / Runtime Metrics を取得する |
| 業務 metrics（手動計装） | `todo.operation.*` を維持する。Micrometer API 維持を第一候補とし、必要なら OTel Metrics API へ移行する |
| OTLP logs | 使用しない。`OTEL_LOGS_EXPORTER=none` を維持する |

## 対象外

- 公開 API `/api/todos` の仕様変更。
- DB schema / Flyway migration の変更。
- Cognito / JWT 検証 / 認可境界の変更。
- Datadog Java Tracer（`dd-java-agent.jar`）の導入。
- OpenTelemetry Logs の OTLP 送信への切り替え。
- アプリログを CloudWatch Logs へ直接送る構成への変更。
- Datadog DBM、Continuous Profiler、App and API Protection の詳細設計。
- Datadog dashboard / monitor / index / exclusion filter の詳細設計。
- load-test / frontend の機能変更。

## ユーザーストーリー / 利用シナリオ

- 運用担当者として、Datadog APM の Todo API trace で JDBC query span を確認し、DB アクセスに起因する遅延を特定したい。
- SRE として、Datadog Metrics で JDBC / database client metrics と JVM Runtime Metrics を確認し、アプリケーション負荷・DB 負荷・GC 影響を横断分析したい。
- 開発者として、アプリコードへ JDBC 計装を個別に追加しなくても、Spring Data JPA / PostgreSQL JDBC 経由の DB 操作が trace と metrics に出る状態を維持したい。
- 運用担当者として、Datadog Logs から `trace_id` で該当 trace へ遷移し、trace から関連 logs へ戻れる既存の調査導線を維持したい。
- 開発者として、Java Agent が自動生成しない業務処理単位の span を、必要な operation に限定して維持または追加したい。
- 開発者として、既存の `todo.operation.count` / `todo.operation.duration` を失わず、Java Agent 導入後も業務成功率と処理時間を確認したい。

## 機能要件

### REQ-OTELAGENT-001 Java Agent 採用

- JVM 起動時に OpenTelemetry Java Agent（`opentelemetry-javaagent.jar`）を attach する。
- Datadog Java Tracer（`dd-java-agent.jar`）は導入しない。
- OpenTelemetry Java Agent と Datadog Java Tracer を同一 JVM に同時 attach しない。
- `opentelemetry-javaagent.jar` は `latest` ではなく明示バージョンで固定する。
- `opentelemetry-javaagent.jar` は runtime 起動時にダウンロードせず、Docker image build 時に取得または配置する。

### REQ-OTELAGENT-002 OTLP export 経路

- Trace は app コンテナから Datadog Agent sidecar の OTLP/gRPC `4317` へ送信する。
- Metrics は app コンテナから Datadog Agent sidecar の OTLP/HTTP `4318` へ送信する。
- Java Agent の protocol 既定値に依存せず、trace / metrics の endpoint と protocol を signal 別に明示する。
- OTLP logs は送信しない。`OTEL_LOGS_EXPORTER=none` を維持する。
- Datadog Agent sidecar は `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT=0.0.0.0:4317` と `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318` を維持する。

### REQ-OTELAGENT-003 Trace / Span 自動計装

- Java Agent により HTTP server / Servlet / Spring Web MVC の span を自動生成する。
- Java Agent により JDBC query span を自動生成する。
- Spring Data JPA / Hibernate 経由の DB 操作は、主に JDBC query span として観測できることを期待値とする。
- 任意の Service / Repository / Controller public method すべてを Java Agent で自動 span 化できるものとは扱わない。
- Trace / Span（業務/手動計装）は自動計装の代替ではなく、REQ-OTELAGENT-004 として別に扱う。

### REQ-OTELAGENT-004 Trace / Span（業務/手動計装）

- Java Agent 自動計装では保証されない業務処理単位の span は、Trace / Span（業務/手動計装）として自動計装とは別の Signal 区分で扱う。
- 必要に応じて OpenTelemetry API、`@WithSpan`、method instrumentation、限定 AOP のいずれかで実装する。
- 送信経路は自動計装 span と同じ app -> OTLP/gRPC `4317` -> Datadog Agent sidecar -> Datadog APM とする。
- 手動業務 span のために Spring Boot OTel starter などの別 SDK / exporter を二重運用しない。
- 対象 operation は `todo.create`、`todo.update`、`todo.delete` など、低カーディナリティで明示的に定義した業務操作に限定する。
- 現行の `PublicMethodTelemetryAspect` による全 public method span 作成を、そのまま存続させることを前提にしない。
- `business.operation`、`result.status` などの業務属性を継続する場合は低カーディナリティ値に限定し、機密値・個人情報・トークン本文を含めない。

### REQ-OTELAGENT-005 JDBC metrics

- Java Agent により JDBC / database client metrics を取得する。
- JDBC metrics の semantic convention stability opt-in を設定する。
- 最終構成では `OTEL_SEMCONV_STABILITY_OPT_IN=database` を第一候補とする。
- 旧 experimental metrics との移行比較が必要な検証時のみ、一時的に `database/dup` を使用してよい。
- SQL sanitizer を無効化しない。
- SQL bind parameter、DB 接続情報、Secrets Manager 由来値、JWT、PII 生値を telemetry に含めない。

### REQ-OTELAGENT-006 Runtime Metrics

- Java Agent により JVM Runtime Metrics を取得する。
- Datadog 上で JVM memory、GC、thread、class loading、process / CPU 関連 metrics のうち Java Agent と Datadog Agent が対応する範囲を確認できるようにする。
- Runtime Metrics は Java Agent 由来を第一候補とする。
- 既存 Micrometer JVM metrics と重複する場合、採用する metric 系列を実装計画で整理する。

### REQ-OTELAGENT-007 業務 metrics 維持

- 既存の `todo.operation.count` と `todo.operation.duration` を維持する。
- 業務 metrics は自動計装では生成されないため、手動計装を残す。
- 第一候補として `BusinessMetricsService` の Micrometer API を維持する。
- Micrometer API を維持する場合は、Java Agent の Micrometer instrumentation を有効化し、`todo.operation.*` が Datadog Metrics に到達することを検証する。
- Micrometer instrumentation で期待どおり export できない場合は、OpenTelemetry Metrics API への移行を検討する。
- Micrometer OTLP Registry と Java Agent metrics exporter を無条件に併用しない。

### REQ-OTELAGENT-008 既存手動 span / AOP の見直し

- `PublicMethodTelemetryAspect` による全 public method span 作成は、Java Agent 導入後に廃止または限定化する。
- Java Agent の HTTP / Spring Web MVC span と `PublicMethodTelemetryAspect` の Controller / Service span が過剰に重複しないようにする。
- `PublicMethodTelemetryAspect` が業務 metrics 記録にも使われている場合は、span 作成責務と metrics 記録責務を分離する。
- AOP を削除する場合は、`todo.operation.*` の記録タイミングが失われないことを保証する。
- 必要な Trace / Span（業務/手動計装）を残す場合も、全 public method ではなく低カーディナリティな業務 operation に限定する。

### REQ-OTELAGENT-009 ログ相関維持

- ログ相関の主キーは `trace_id` / `span_id` とする。
- `trace_id` は OpenTelemetry `SpanContext.traceId` 由来の 32 文字小文字 hex とする。
- `span_id` は OpenTelemetry `SpanContext.spanId` 由来の 16 文字小文字 hex とする。
- `RequestLoggingContextFilter` は `trace_id` / `span_id` を独自生成・上書きしない。
- `X-Amzn-Trace-Id` は `x_amzn_trace_id` として補助情報に限定する。
- `traceId` / `spanId` の旧キーを JSON ログトップレベルへ復活させない。
- Java Agent の Logback MDC instrumentation を第一候補とし、既存 JSON ログに `trace_id` / `span_id` が出ることを確認する。
- Java Agent の MDC だけで既存ログ要件を満たせない場合のみ、既存の `opentelemetry-logback-appender-1.0` または最小補助実装を残す。

### REQ-OTELAGENT-010 Unified Service Tagging

- `DD_SERVICE=todo-backend` を維持する。
- `DD_ENV` は `dev` / `stg` / `prod` のいずれかとする。
- `DD_VERSION` は `BackendImageDeploymentConstruct.imageTag` 由来値を使用する。
- `OTEL_SERVICE_NAME=todo-backend` を維持する。
- `OTEL_RESOURCE_ATTRIBUTES` に `service.name=todo-backend`、`service.version=<imageTag>`、`deployment.environment=<env>` を含める。
- `DD_SERVICE` / `DD_ENV` / `DD_VERSION` を `DD_TAGS` に重複定義しない。
- `DD_TAGS` の補助タグ（`team:o11y-CoE`、`system:todo`、`aws_account:<accountId>`）を維持する。

### REQ-OTELAGENT-011 依存関係整理

- Java Agent が trace / metrics exporter と自動計装の主経路になるため、`spring-boot-starter-opentelemetry` の継続要否を見直す。
- 手動 OpenTelemetry API が必要な場合は、`opentelemetry-api` など必要最小限の依存に整理する。
- Java Agent の Logback MDC instrumentation で相関要件を満たせる場合、`opentelemetry-logback-appender-1.0` と `OpenTelemetryLogbackAppenderInitializer` を削除候補とする。
- AOP が不要になった場合、`spring-aop`、`aspectjweaver`、`spring.aop.proxy-target-class=true` の継続要否を見直す。

### REQ-OTELAGENT-012 Datadog Agent image pin

- Datadog Agent image は `latest` のままにせず、OTLP traces / metrics ingest を検証済みの 7.x 具体バージョンへ pin する。
- 採用バージョンは ADR-0004 と Datadog 公式 docs の OTLP ingest 対応条件を満たすことを確認する。
- 採用バージョンで `4317` / `4318` receiver が起動し、trace / metrics を受信できることを検証する。

### REQ-OTELAGENT-013 ドキュメント更新

- Java Agent 導入後の O11y 構成を `backend/README.md` に反映する。
- Signal 別経路が Java Agent 中心へ変わるため、`docs/infra/o11y.md` を更新する。
- `trace_id` / `span_id` の注入方式が変わる場合、`docs/backend/logging.md` を更新する。
- ECS app コンテナの O11y 環境変数や Datadog Agent image pin を変更する場合、`infra/README.md` を更新する。
- ADR-0003 は Java Agent 導入後に前提が変わるため、更新する。

## 非機能要件

### 運用性

- Datadog 上で `service:todo-backend env:<env> version:<imageTag>` を基準に Logs / Traces / Metrics を横断調査できる。
- Datadog Agent sidecar と FireLens sidecar の診断ログは CloudWatch Logs で確認できる。
- Java Agent 起動失敗、OTLP 送信失敗、Datadog Agent 受信失敗を切り分けられる。

### 保守性

- JDBC span / metrics / Runtime Metrics は Java Agent 自動計装を主とし、アプリコードに個別 JDBC 計装を追加しない。
- 自動計装と手動計装の責務を分離し、同一意味の telemetry を重複生成しない。
- `PublicMethodTelemetryAspect` を残す場合は対象と目的を限定する。

### セキュリティ / コンプライアンス

- シークレット、DB 接続情報、JWT 本文、Authorization header、Cookie、個人情報生値を logs / spans / metrics に含めない。
- SQL sanitizer を無効化しない。
- `ownerSubject` の生値は引き続きログ出力せず、`ownerSubjectHash` を使用する。
- Java Agent jar の取得元とバージョンを固定し、可能な場合は checksum を検証する。

### 性能 / コスト

- Java Agent 導入により API 応答性能が許容できないほど劣化しないことを確認する。
- 高カーディナリティ属性を span attributes / metrics attributes に追加しない。
- 全 public method span のような過剰な span 生成を避け、trace 可読性と Datadog 取り込み量を制御する。
- Java Agent debug logging は本番で常時有効にしない。

### 互換性

- `/api/todos` の API 契約を変更しない。
- Flyway / JPA / PostgreSQL の schema 契約を変更しない。
- Cognito issuer / JWT 検証 / owner_subject 境界を変更しない。
- Java 21 と現行 Spring Boot 4.0.5 で動作する。

## 受け入れ条件

### backend / local

- `opentelemetry-javaagent.jar` が JVM に attach されていることを起動ログまたは telemetry で確認できる。
- `./mvnw test` が成功する。
- 必要に応じて、Java Agent attach 状態のコンテナ起動確認が成功する。
- `/actuator/health` が利用でき、ALB health check 用の公開仕様が変わらない。
- `/api/todos` の既存 controller / service / repository テストが成功する。
- JSON ログに `trace_id` / `span_id` が 32/16 文字小文字 hex で出力される。
- `x_amzn_trace_id` は補助情報として出力され、`trace_id` を上書きしない。
- `traceId` / `spanId` の旧キーが復活していない。

### Datadog APM

- `/api/todos` のリクエストが Datadog APM の trace として表示される。
- HTTP server / Spring Web MVC span が確認できる。
- Todo の DB 操作に対して JDBC query span が確認できる。
- Trace / Span（業務/手動計装）を残す、または追加する場合、対象 operation の span が Datadog APM で確認できる。
- `service:todo-backend`、`env:<env>`、`version:<imageTag>` で trace を絞り込める。
- Trace から関連 Logs へ遷移できる。
- Logs から該当 Trace へ遷移できる。

### Datadog Metrics

- JDBC / database client metrics が確認できる。
- JVM Runtime Metrics が確認できる。
- 既存 business metrics `todo.operation.count` / `todo.operation.duration` が確認できる。
- Metrics に `service` / `env` / `version` が整合して付与されている。
- 高カーディナリティ値や機密値が metrics attributes に含まれていない。

### infra / ECS

- `TodoBackendContainer` に Java Agent attach 設定が入っている。
- Datadog Agent image が `latest` ではなく明示バージョンで pin されている。
- Datadog Agent sidecar の `4317` / `4318` receiver が起動している。
- app コンテナから `localhost:4317` / `localhost:4318` へ telemetry を送信できる。
- `DatadogAgentContainer` の CloudWatch Logs に OTLP 受信・転送エラーが継続的に出ていない。
- `LogRouterContainer` の CloudWatch Logs に FireLens 転送エラーが継続的に出ていない。

### docs

- `backend/README.md` に Java Agent 導入後の O11y 主要環境変数と起動前提が記載されている。
- `docs/infra/o11y.md` に Java Agent 導入後の Signal 別経路が記載されている。
- `docs/backend/logging.md` に Java Agent 導入後も `trace_id` / `span_id` 主相関を維持する方針が記載されている。
- ADR-0003 / ADR-0004 の更新要否が判断されている。

## 制約

- ADR-0004 の決定に反して Datadog Java Tracer を採用しない。
- OpenTelemetry Java Agent と Spring Boot OTel starter の SDK / exporter を二重運用しない。
- Java Agent metrics exporter と Micrometer OTLP Registry を無条件に併用しない。
- OTLP logs は使用しない。
- app ログの主経路は FireLens とし、CloudWatch Logs へ直接二重送信しない。
- `DD_SERVICE` / `DD_ENV` / `DD_VERSION` と `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES` の値を矛盾させない。
- `DD_VERSION` / `service.version` は `BackendImageDeploymentConstruct.imageTag` を単一ソースとする。
- `DD_ENV` は `dev` / `stg` / `prod` のみ許容する。
- シークレットや認証情報をコード、ログ、span attributes、metrics attributes に含めない。
- 変更は依頼範囲に限定し、API 契約、DB schema、認証認可仕様を変更しない。

## 依存関係

### リポジトリ内

- `docs/adr/adr-0004-OTel-Java-Agent.md`
- `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`
- `docs/adr/adr-0002-trace-correlation-otel-datadog.md`
- `docs/adr/adr-0003-OTel-DatadogAgent-settings.md`
- `docs/infra/o11y.md`
- `docs/backend/logging.md`
- `specs/005-OTel-Java-Agent-conversion/OTel-Java-Agent-Outline-Design.md`
- `backend/pom.xml`
- `backend/Dockerfile`
- `backend/src/main/resources/application.properties`
- `backend/src/main/java/com/example/backend/telemetry/`
- `infra/lib/constructs/todo-backend-ecs-service-construct.ts`
- `infra/lib/constructs/backend-image-deployment-construct.ts`

### 外部 / 実行環境

- OpenTelemetry Java Agent。
- Datadog Agent OTLP ingest。
- ECS Fargate app container / sidecar container 同一 task 内通信。
- AWS Secrets Manager の DB 接続情報と Datadog API Key。
- Datadog Logs / APM / Metrics の受信、mapping、remapper 設定。

## 未確定事項 / 要確認事項

- OpenTelemetry Java Agent の採用バージョン。
  - 候補は概要設計書上では `2.28.1` だが、実装前に release note と互換性を確認する。
- `opentelemetry-javaagent.jar` の取得元、checksum 検証方法、Docker cache 方針。
- Java Agent attach 方法を `JAVA_TOOL_OPTIONS` にするか、ENTRYPOINT へ直接 `-javaagent` を追加するか。
  - 第一候補は `JAVA_TOOL_OPTIONS`。
- Datadog Agent image の pin バージョン。
  - 現行は `public.ecr.aws/datadog/agent:latest` のため、検証済み 7.x 具体バージョンへ変更する必要がある。
- JDBC metrics の semantic convention stability opt-in を最終的に `database` とする。
- `BusinessMetricsService` を Micrometer API のまま維持する。
- `OTEL_INSTRUMENTATION_MICROMETER_ENABLED=true` で `todo.operation.*` が Datadog Metrics に届くか。
- `PublicMethodTelemetryAspect`の全 public method span 作成は、Java Agent 導入後の重複要因になるため原則削除または限定化してください。
  - 業務 metrics が Aspect に依存している場合は、span 作成責務と metrics 記録責務を分離してください。
  - 必要な業務 span がある場合も、全 public method ではなく todo.create など低カーディナリティな操作に限定してください。
- Java Agent の Logback MDC instrumentation だけで既存 JSON ログのトップレベルに `trace_id` / `span_id` が出力されるか。
- `opentelemetry-logback-appender-1.0` と Java Agent Logback MDC instrumentation の併用を避けた場合、ログ相関要件を満たせるか。
- Runtime Metrics と既存 Micrometer JVM metrics が Datadog 上で重複するか。
- 「Datadog 側で `trace_id` を予約属性として扱うための Preprocessing / Trace ID remapper 設定が必要か。」に関して
  - アプリログのキー名は trace_id / span_id を維持してください。
  - コード側で Datadog 固有の別名へ安易に変更しないでください。
  - Datadog 上で Logs から Trace、Trace から Logs へ遷移できない場合は、Datadog Logs pipeline の Trace ID remapper または Preprocessing 設定が必要かを確認事項として記載してください。

## 参考情報

- OpenTelemetry Java Agent: https://opentelemetry.io/docs/zero-code/java/agent/
- OpenTelemetry Java Agent Configuration: https://opentelemetry.io/docs/zero-code/java/agent/configuration/
- OpenTelemetry Java Agent Supported Libraries: https://opentelemetry.io/docs/zero-code/java/agent/supported-libraries/
- Datadog OTLP Ingestion by the Datadog Agent: https://docs.datadoghq.com/opentelemetry/setup/otlp_ingest_in_the_agent/
- Datadog OpenTelemetry Runtime Metrics: https://docs.datadoghq.com/opentelemetry/integrations/runtime_metrics/?tab=java
