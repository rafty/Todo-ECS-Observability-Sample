# Backend ログ / 業務テレメトリ設計

## この文書の目的

- `backend/` のアプリケーションログ実装方針を、開発者・運用者が同じ前提で参照できるようにする。
- AWS 実行環境（ECS -> FireLens -> Datadog Logs）で調査可能なログキーと運用ルールを明確化する。
- OpenTelemetry Java Agent 導入後も `trace_id` / `span_id` を主相関キーとして維持する。
- `src/main/java/com/example/backend/logging` と `src/main/java/com/example/backend/telemetry` の責務を明確にし、保守時にログ・span・metrics の境界を誤解しないようにする。

## ログ分類

- 業務ログ（`eventType=BUSINESS`）
  - 正常系の重要イベント、状態遷移、検索結果要約を記録する。
- 監査ログ（`eventType=AUDIT`）
  - 書き込み操作（`POST`/`PUT`/`DELETE`）の主体・対象・結果を記録する。
- 異常系ログ（`eventType=ERROR`）
  - 4xx は `WARN`、未処理例外（5xx）は `ERROR` で記録する。
- デバッグログ（`eventType=DEBUG`）
  - 正規化結果や分岐確認など、調査用途の詳細情報を記録する。

## 実装の中心

`logging` パッケージはログ文脈と主体識別子の安全な表現を担当し、`telemetry` パッケージは OpenTelemetry Java Agent と連携する業務 span / metrics を担当する。

| ファイル | 主な責務 | 保守時の注意 |
| --- | --- | --- |
| `logging/RequestLoggingContextFilter.java` | リクエスト開始時に `requestId`、`path`、`httpMethod`、`x_amzn_trace_id` を MDC に入れる。未処理例外は `eventType=ERROR` として記録し、最後に `MDC.clear()` する。 | `trace_id` / `span_id` は独自生成しない。OpenTelemetry Java Agent または `TodoOperationSpanService` 由来値を壊さない。 |
| `logging/OwnerSubjectHashService.java` | JWT `sub` などの主体識別子を SHA-256 hash に変換し、ログには `ownerSubjectHash` だけを出す。 | 生の `ownerSubject` はログへ出さない。空値は `anonymous`、hash 不能時は `hash-unavailable` に寄せる。 |
| `telemetry/OpenTelemetryApiConfig.java` | Java Agent が設定する `GlobalOpenTelemetry` を Spring Bean として公開する。 | アプリ内で別の OpenTelemetry SDK / exporter を起動しない。 |
| `telemetry/TodoOperationTelemetryAspect.java` | `TodoServiceImpl` の Todo 操作だけを AOP で囲み、業務 span と `todo.operation.*` metrics を記録する。 | 旧 `PublicMethodTelemetryAspect` のような全 public method 計装へ戻さない。対象 operation は低カーディナリティ固定値に限定する。 |
| `telemetry/TodoOperationSpanService.java` | OpenTelemetry API で手動業務 span を作成し、成功/失敗 status と低カーディナリティ attribute を付与する。必要な場合だけ `trace_id` / `span_id` を MDC に反映し、終了時に復元する。 | MDC の外側値を必ず復元する。`SpanContext` が invalid な場合は相関 ID を作らない。 |
| `telemetry/BusinessMetricsService.java` | Micrometer `MeterRegistry` に `todo.operation.count` と `todo.operation.duration` を記録する。 | Datadog への export は Java Agent Micrometer instrumentation に任せる。アプリ側で OTel Metrics API と二重実装しない。 |
| `telemetry/TelemetryEnvironmentValidator.java` | `DD_ENV` を `dev` / `stg` / `prod` に制限し、タグ不整合を起動時に検知する。 | 新しい環境名を追加する場合は infra の環境定義と同時に更新する。 |

## 構造化ログ設定

`src/main/resources/application.properties` で Spring Boot の構造化 JSON ログを有効にしている。

| 設定 | 役割 |
| --- | --- |
| `logging.structured.format.console=${LOGGING_STRUCTURED_FORMAT_CONSOLE:logstash}` | コンソール出力を JSON へ統一し、ECS stdout から FireLens へ渡せる形にする。 |
| `logging.structured.json.add.service=${DD_SERVICE:${spring.application.name}}` | Datadog Unified Service Tagging の `service` をログへ付与する。 |
| `logging.structured.json.add.env=${DD_ENV:dev}` | Datadog Unified Service Tagging の `env` をログへ付与する。 |
| `logging.structured.json.add.version=${DD_VERSION:unknown-version}` | Datadog Unified Service Tagging の `version` をログへ付与する。 |
| `logging.structured.json.exclude=traceId,spanId` | 旧 camelCase キーを JSON トップレベルへ復活させない。 |
| `spring.aop.proxy-target-class=true` | `TodoServiceImpl` に限定した業務 span / metrics AOP を実装クラスへ確実に適用する。 |

ログレベルは `LOGGING_LEVEL_ROOT` と `LOGGING_LEVEL_COM_EXAMPLE_BACKEND` で変更できる。Datadog 取り込み量に直結するため、`DEBUG` は調査時だけ一時的に使う。

## ログイベント出力箇所

| 実装箇所 | 主なイベント |
| --- | --- |
| `TodoController` | API レスポンス契約に近い業務ログと監査ログを出す。`LIST` / `GET` は `BUSINESS`、`CREATE` / `UPDATE` / `DELETE` は `AUDIT`。 |
| `TodoServiceImpl` | 永続化後の業務イベント、状態遷移、検索条件正規化の `DEBUG` を出す。 |
| `RequestLoggingContextFilter` | AWS ALB 由来の `X-Amzn-Trace-Id` 補助文脈と未処理例外を出す。 |

Controller と Service の両方にログがあるのは、HTTP 契約に近い監査証跡と、永続化・状態遷移に近い業務イベントを分けるためである。どちらにも JWT `sub` 生値やリクエスト本文全文は出さない。

## トレース計装方針（本サンプル固有）

- HTTP server / Servlet / Spring Web MVC / JDBC などの framework / library span は OpenTelemetry Java Agent の自動計装を主経路とする。
- Java Agent は任意の業務メソッドをすべて自動 span 化しない。
- 業務処理単位の span は `TodoOperationTelemetryAspect` により `TodoServiceImpl` の Todo 操作だけに限定する。
- 旧 `PublicMethodTelemetryAspect` のように Spring 管理 Bean の全 public method を span 化する方式は採用しない。

| 対象メソッド | operation |
| --- | --- |
| `TodoServiceImpl.listTodos` | `todo.list` |
| `TodoServiceImpl.getTodo` | `todo.get` |
| `TodoServiceImpl.createTodo` | `todo.create` |
| `TodoServiceImpl.updateTodo` | `todo.update` |
| `TodoServiceImpl.deleteTodo` | `todo.delete` |

手動業務 span の attribute は `business.operation`、`result.status` など低カーディナリティ値に限定する。JWT、Authorization header、Cookie、DB 接続情報、SQL bind parameter、PII 生値は span attribute に含めない。

## 監査対象範囲

- 監査ログ対象:
  - `POST /api/todos`
  - `PUT /api/todos/{todoId}`
  - `DELETE /api/todos/{todoId}`
- 監査ログ対象外:
  - 読み取り操作（`GET`/`LIST`）

補足:

- 本 feature で扱う監査はアプリケーション監査ログのみ。
- Aurora 側監査ログ（`pgaudit` などの DB 監査）は対象外。

## JSON フィールド定義

Datadog Logs で検索しやすいよう、ログは JSON 構造化形式を前提とする。

主要フィールド:

- `timestamp`
- `level`
- `logger`
- `message`
- `service`
- `env`
- `version`
- `requestId`
- `trace_id`
- `span_id`
- `x_amzn_trace_id`
- `path`
- `httpMethod`
- `httpStatus`
- `eventType`
- `action`
- `ownerSubjectHash`
- `todoId`（対象がある場合）

## 相関 ID 方針

- `requestId`
  - `X-Request-Id` が来ていれば利用し、未指定時はサーバー側で採番する。
- `trace_id` / `span_id`
  - OpenTelemetry の `SpanContext` 由来値を正とし、Datadog APM 相関の主キーとして利用する。
  - 第一候補は OpenTelemetry Java Agent の Logback MDC instrumentation による MDC 注入とする。
  - `trace_id` は 32 文字小文字 hex、`span_id` は 16 文字小文字 hex を使用する。
  - `TodoOperationSpanService` は有効な `SpanContext` がある場合だけ `trace_id` / `span_id` を MDC に反映し、処理後に既存 MDC 値を復元する。
- `x_amzn_trace_id`
  - `X-Amzn-Trace-Id` の `Root=` 値を補助情報として保持し、AWS 側ログとの突合に利用する。
  - `trace_id` を上書きしない。
- `traceId` / `spanId`
  - 旧キーとして扱い、JSON ログトップレベルへ復活させない。

`RequestLoggingContextFilter` は `requestId`、`path`、`httpMethod`、`x_amzn_trace_id` のリクエスト補助情報を MDC に設定する。`trace_id` / `span_id` は独自生成または上書きしない。

構造化ログでは MDC と `addKeyValue` に同じキーを重複させると JSON 出力時に例外となる可能性がある。そのため、`path`、`httpMethod`、`x_amzn_trace_id` など MDC に存在するキーを同一イベントで再度 `addKeyValue` しない。

## Datadog Logs pipeline 方針

初期実装では Datadog Logs pipeline の Preprocessing / Trace ID remapper は追加しない。

理由:

- ADR-0002 の方針どおり、アプリ側ログでは OpenTelemetry 形式の `trace_id` / `span_id` を正とする。
- Java Agent Logback MDC instrumentation により Datadog が標準的に相関できる形を第一候補にする。
- Datadog 側設定不足を理由に、アプリ側キーを `dd.trace_id` / `dd.span_id` へ安易に変更しない。

Datadog 上で Logs -> Trace / Trace -> Logs の双方向遷移が成立しない場合のみ、Datadog Logs pipeline 側の追加設定を検討する。

## 主体識別子（ownerSubject）の扱い

- JWT `sub` の生値（`ownerSubject`）はログ出力しない。
- ログには `ownerSubjectHash`（SHA-256）を出力する。
- これにより、主体の生値露出を避けつつ同一主体の相関調査を可能にする。

## ログレベル運用

- 既定レベル: `INFO`
- 主要運用:
  - `WARN`: クライアント修正可能な 4xx 異常
  - `ERROR`: 未処理例外などの 5xx 異常
  - `DEBUG`: 調査時のみ一時的に有効化
- 動的変更:
  - `LOGGING_LEVEL_ROOT`
  - `LOGGING_LEVEL_COM_EXAMPLE_BACKEND`

Java Agent debug logging は本番で常時有効にしない。

## 機密情報の非出力ルール

以下はログへ出力しない:

- JWT 本文
- Authorization ヘッダー
- Cookie
- Secrets Manager 由来の接続情報
- SQL bind parameter
- 個人情報の生値
- `ownerSubject(sub)` の生値

## AWS 運用前提

```mermaid
flowchart TB
  App["Spring Boot + OTel Java Agent on ECS"] --> Stdout["Container STDOUT JSON"]
  Stdout --> FireLens["FireLens / Fluent Bit"]
  FireLens --> DDLogs["Datadog Logs"]
  FireLens -->|"sidecar diagnostics"| CWLogs["CloudWatch Logs"]
  App -->|"OTLP traces gRPC 4317"| DDAgent["Datadog Agent"]
  App -->|"OTLP metrics HTTP 4318"| DDAgent
  DDAgent --> DDApm["Datadog APM / Metrics"]
  DDAgent -->|"sidecar diagnostics"| CWLogs
```

- backend はコンテナ標準出力へログ出力する。
- ログ相関の主調査画面は Datadog（Logs/APM）とする。
- CloudWatch Logs は sidecar（`log_router` / `datadog-agent`）の診断用途・短期保持用途に限定する。
- OTLP logs は使わない。

## 保持期間ポリシー

- Datadog Logs の保持期間、Index、Exclusion Filter は infra/Datadog 運用設計に従う。
- CloudWatch Logs の保持期間は診断ログ用途に限定し、環境別保持日数は `docs/infra/o11y.md` の方針（`dev=3日, stg=7日, prod=14日`）に従う。

## 確認事項 / TODO

- 確認済み: prod canary 相当の deploy 後、Java Agent Logback MDC instrumentation だけで Datadog Logs に `trace_id` / `span_id` が出る。
- 確認済み: `trace_id` は 32 文字小文字 hex、`span_id` は 16 文字小文字 hex として Datadog Logs に取り込まれる。
- 確認済み: `traceId` / `spanId` の旧キーは Datadog Logs の sample では復活していない。
- 確認済み: Datadog API 上では同一 `trace_id` の Logs / Spans を相互検索できるため、初期実装では Logs pipeline の Preprocessing / Trace ID remapper を追加しない。
- 確認事項: ブラウザ UI 上の Trace から Logs / Logs から Trace へのクリック遷移は、必要に応じて運用確認で実施する。

## 関連

- [backend 入口 README](../../backend/README.md)
- [backend ドキュメント入口](./README.md)
- [infra 入口 README](../../infra/README.md)
- [Observability 仕様](../infra/o11y.md)
- [ADR-0002: OpenTelemetry `trace_id` / `span_id` を正とする Datadog ログ相関方式](../adr/adr-0002-trace-correlation-otel-datadog.md)
- [ADR-0004: APMエージェントの選定](../adr/adr-0004-OTel-Java-Agent.md)
