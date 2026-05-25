# specs-draft.md: backend(Spring Boot) 向け Datadog / OpenTelemetry 実装修正（fix-02）

- Status: Draft
- Date: 2026-05-25
- Related ADR: `./ADR-datadog-tagging-strategy.md`
- Related ADR: `../../docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`, `../../docs/adr/adr-0002-trace-correlation-otel-datadog.md`
- Related specs: `../003-OTel-to-backend/specs-draft.md`（初版）, `../004-Datadog-agent-to-cdk/specs-draft.md`（infra 側）
- Target: `backend/` の Spring Boot 実装（既存 OTel 対応の修正）

## 1. 概要

本仕様は、`specs/003-OTel-to-backend/` で実装済みの backend 側オブザーバビリティ実装を、Datadog タグ戦略とログ相関要件の確定内容に合わせて修正するための要件定義である。  
本仕様の主眼は、`trace_id` / `span_id` 相関の安定化、Unified Service Tagging の厳密化、および多アカウント運用を見据えたタグ整合である。

## 2. ゴール

### 2.1 機能ゴール

- backend ログのトップレベルに `trace_id` / `span_id` が出力される。
- `trace_id` / `span_id` は OpenTelemetry `SpanContext` を正とし、`traceId` 等の曖昧キー名を新規導入しない。
- backend 側で設定する OTel 変数が Datadog Agent 側タグ（`DD_SERVICE` / `DD_ENV` / `DD_VERSION`）と矛盾しない。
- Datadog 上で Logs ↔ Traces が双方向に遷移できる。

### 2.2 非機能ゴール

- 既存の Controller / Service / Repository の責務分離を崩さない。
- 業務コード改修を最小限に抑える（主にログ相関・設定・初期化に限定）。
- ログ出力禁止情報（トークン本文、PII、シークレット）を維持する。

## 3. 非ゴール

- FireLens / Datadog Agent コンテナ定義の実装変更（infra 側仕様の対象）。
- OpenTelemetry Logs API への業務ログ全面移行。
- Datadog ダッシュボード・Monitor の詳細設計。

## 4. 要件

### 4.1 ログ相関要件

#### REQ-BE-FIX02-LOG-001: 相関キーの正

- ログ相関の主キーは `trace_id` / `span_id` とする。
- 値は `Span.current().getSpanContext()` 由来値を正とする。
- `trace_id` は 32 文字小文字 hex、`span_id` は 16 文字小文字 hex を満たすこと。

#### REQ-BE-FIX02-LOG-002: 補助キー

- `X-Amzn-Trace-Id` は補助情報としてのみ扱い、必要時のみ `x_amzn_trace_id` で保持する。
- `trace_id` / `span_id` を `RequestLoggingContextFilter` 等で独自生成・上書きしない。

#### REQ-BE-FIX02-LOG-003: Logback Appender 初期化

- `opentelemetry-logback-appender-1.0` を依存追加する。
- Spring Boot 起動時に `OpenTelemetryAppender.install(openTelemetry)` を実行する初期化 Bean を実装する。
- Appender 初期化失敗時は、起動不能化ではなく劣化運転（相関キー欠落）を避けるため、失敗検知ログを出力して原因を把握可能にする。

### 4.2 タグ整合要件

#### REQ-BE-FIX02-TAG-001: backend 側 OTel 変数

backend コンテナは次の前提で動作する。

- `OTEL_SERVICE_NAME=todo-backend`
- `OTEL_RESOURCE_ATTRIBUTES` に `deployment.environment=<env>` と `service.version=<version>` を含める
- `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`

#### REQ-BE-FIX02-TAG-002: infra 側タグとの契約

- backend 仕様として、infra 側で `DD_SERVICE` / `DD_ENV` / `DD_VERSION` が設定されることを前提にする。
- `DD_VERSION` は `infra/lib/constructs/backend-image-deployment-construct.ts` の `backendDockerImageAsset.imageTag` 由来値が設定されることを前提にする。
- backend 仕様として、infra 側で `DD_TAGS=team:<team> aws_account:<aws-account-id> system:todo` が設定されることを前提にする。
- backend から送出するテレメトリ属性と Datadog タグに矛盾がないことを確認観点に含める。

## 5. 実装対象

### 5.1 依存関係

- `pom.xml` に `opentelemetry-logback-appender-1.0` を追加する。

### 5.2 設定・初期化

- OpenTelemetry Logback Appender の初期化 Bean を追加する。
- 必要に応じて `application.properties` の OTel 関連設定を明示し、環境変数優先の方針を崩さない。

### 5.3 ログ出力フォーマット

- JSON ログのトップレベルに `trace_id` / `span_id` が出力されることを維持する。
- 既存 `traceId` キーの新規利用は禁止する。

## 6. 受入条件

- [ ] backend ログに `trace_id` / `span_id` がトップレベルで出力される。
- [ ] `trace_id` が 32 文字小文字 hex、`span_id` が 16 文字小文字 hex である。
- [ ] `x_amzn_trace_id` は補助情報としてのみ出力され、主キーとして扱われない。
- [ ] `traceId` キー名が新規仕様・新規実装で使用されていない。
- [ ] Datadog で Trace ↔ Logs の双方向遷移ができる。
- [ ] `service` / `env` / `version` が logs/traces で一致する。

## 7. テスト方針

### 7.1 backend 単体・結合テスト

- 既存テスト（ログ出力・Telemetry 関連）に対して、`trace_id` / `span_id` の出力と形式チェックを追加・更新する。
- `RequestLoggingContextFilter` が `trace_id` / `span_id` を上書きしないことを検証する。

### 7.2 実運用確認（ECS 連携）

- Logs Explorer で `@trace_id:* @span_id:*` を確認する。
- APM Trace から Logs、Logs から Trace の遷移を確認する。
- `service:todo-backend` と `env:<env>`、`version:<version>` の整合を確認する。

## 8. リスクと対策

| リスク | 対策 |
|---|---|
| Appender 初期化漏れで `trace_id` / `span_id` が出ない | 初期化 Bean を必須化し、テストで検出する |
| 旧キー `traceId` が残存する | 新規仕様で使用禁止を明文化し、レビュー観点に追加する |
| Datadog 側で `trace_id` 認識されない | JSON logs preprocessing または Trace ID remapper 設定を確認する |

## 9. 未決事項

- Datadog Site（`datadoghq.com` / `ap1.datadoghq.com`）の最終値は環境ごとに確定する。

## 10. 参考資料

- Datadog Correlate OpenTelemetry Traces and Logs: https://docs.datadoghq.com/opentelemetry/correlate/logs_and_traces/
- Datadog Unified Service Tagging: https://docs.datadoghq.com/getting_started/tagging/unified_service_tagging/
- OpenTelemetry Java SDK Configuration: https://opentelemetry.io/docs/languages/java/configuration/
- OpenTelemetry Logback Appender README: https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/logback/logback-appender-1.0/library