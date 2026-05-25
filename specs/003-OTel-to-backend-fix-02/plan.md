# Plan: OTel-to-backend fix-02（Datadog タグ戦略・ログ相関修正）

## 実装方針
- `specs.md` の要件（`REQ-SPEC-FIX02-001`〜`005`）を、既存 `backend/` 実装の最小差分改修で満たす。
- `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md` の OTLP/gRPC・Unified Service Tagging 方針と、`docs/adr/adr-0002-trace-correlation-otel-datadog.md` の `trace_id` / `span_id` 正規化方針を実装計画の基準とする。
- 変更は観測性レイヤ（ログ相関・設定・初期化・テスト）に限定し、Controller / Service / Repository の業務責務は変更しない。
- backend 単体で完結しない項目（Datadog 側 remapper、infra 側環境変数注入）は契約前提として明示し、責任分界を維持する。

## 変更対象
- `backend/pom.xml`
  - `opentelemetry-logback-appender-1.0` 追加方針の適用。
- `backend/src/main/java/com/example/backend/logging/`（特に `RequestLoggingContextFilter` 周辺）
  - `trace_id` / `span_id` を独自生成・上書きしない方針への整合。
  - `x_amzn_trace_id` を補助情報として扱うための整理。
- `backend/src/main/java/com/example/backend/telemetry/` または既存初期化レイヤ
  - `OpenTelemetryAppender.install(openTelemetry)` の起動時初期化処理追加。
  - 初期化失敗時の検知可能ログ出力の実装。
- `backend/src/main/resources/application.properties`
  - 必要最小限の OTel 関連設定確認（環境変数優先を維持）。
- `backend/src/test/java/com/example/backend/logging/` / `telemetry/` / `controllers/`
  - `trace_id` / `span_id` 形式、主従キー、上書き禁止の検証追加・更新。
- 参照のみ（変更想定なし）
  - `infra/lib/constructs/backend-image-deployment-construct.ts`（`backendDockerImageAsset.imageTag`）
  - `infra/lib/config/environment-config.ts`（`team` / `service-name` / `system-name` 共通値）

## 変更しないもの
- `infra/` の ECS タスク定義、Datadog Agent/FireLens 設定、CloudWatch Logs 保持期間。
- backend の業務 API 契約、DB スキーマ、認証・認可仕様。
- Datadog ダッシュボード/Monitor/Index/Exclusion Filter の詳細設計実装。
- OpenTelemetry Logs API への全面移行。

## 技術方針
- 既存パターン再利用
  - Spring Boot の既存 Bean 構成と logging/telemetry パッケージ構造を優先利用する。
  - 既存テストスタイル（JUnit + 既存アサーションパターン）を踏襲し、差分最小で検証を拡張する。
- 新規コンポーネント方針
  - Appender 初期化用の小さな専用設定クラス（または既存設定クラスへの限定追記）で対応し、業務クラスへ混入させない。
- 設定方針
  - `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`（OTLP/gRPC）前提を維持する。
  - `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES` は infra で注入される `DD_SERVICE` / `DD_ENV` / `DD_VERSION` と矛盾しない運用を前提にする。
- 依存追加方針
  - `opentelemetry-logback-appender-1.0` のみを追加候補とし、不要なライブラリ追加は行わない。
- ADR 準拠方針
  - ADR-0001: sidecar 連携・タグ主キー（`service`/`env`/`version`）・コスト配慮を遵守。
  - ADR-0002: 相関キー主方式を `trace_id` / `span_id` に固定し、`traceId` 新規採用を禁止。

## データや契約への影響
- DB スキーマ
  - 影響なし。
- API 契約
  - 影響なし（レスポンススキーマ変更なし）。
- イベント契約
  - 該当なし。
- 環境変数
  - backend 側は `OTEL_SERVICE_NAME`、`OTEL_RESOURCE_ATTRIBUTES`、`OTEL_EXPORTER_OTLP_ENDPOINT` の整合を前提化。
  - infra 側契約として `DD_SERVICE` / `DD_ENV` / `DD_VERSION` / `DD_TAGS` の注入前提を維持。
  - `DD_VERSION` / `service.version` のソースは `backendDockerImageAsset.imageTag` を単一ソースとする。
- Secret / 設定値
  - 新規 secret 追加なし。既存 secret 取り扱い方針を維持。
- デプロイ/運用
  - ログ相関キー統一により Datadog の Trace ↔ Logs 調査導線を安定化。
  - Datadog 側 Preprocessing/Remapper 未整備時は相関不成立リスクが残るため、運用手順で補完する。

## リスク
- 破壊的変更の可能性
  - `RequestLoggingContextFilter` の変更で既存ログ項目が欠落するリスク。
- 互換性への影響
  - `traceId` 依存の既存運用手順が残っている場合、監視/検索クエリの見直しが必要。
- 運用影響
  - Datadog 側 remapper 未適用だと `trace_id` の予約属性認識が不安定になる可能性。
- セキュリティ影響
  - 相関キー追加時に機微情報を混入しないこと（Authorization、トークン本文、PII を出力しない）。
- 監視影響
  - `service` / `env` / `version` 不整合時、Logs/APM の横断分析が分断される。

## 検証方針
- 単体テスト
  - `trace_id`（32hex）/`span_id`（16hex）形式検証。
  - `RequestLoggingContextFilter` が主キーを上書きしない検証。
  - `x_amzn_trace_id` を補助キーとして扱う検証。
- 結合テスト（backend 範囲）
  - 既存 telemetry/logging テストで起動時初期化（Appender install）とログ出力項目を確認。
- 実運用確認（ECS/Datadog）
  - Logs Explorer: `service:<service-name> env:<env> @trace_id:* @span_id:*`
  - Trace Explorer: `service:<service-name> env:<env> version:<version>`
  - Trace → Logs / Logs → Trace の双方向遷移確認。
- 実行順
  - 変更近傍の最小テストから実施し、必要に応じて backend テスト全体へ拡大。

## ドキュメント更新方針
- 更新対象（必要時）
  - `specs/003-OTel-to-backend-fix-02/tasks.md`（本 `plan.md` 確定後に作成）。
  - `docs/adr/` は方針変更が発生した場合のみ更新（現時点は参照のみ）。
  - `backend/README.md` は起動手順・主要コマンド・構成説明に変更が生じる場合のみ更新。
- 更新しない想定
  - 本計画作成時点では ADR の新規追加は不要。

## 実施順序
1. 事前整合確認
   - `specs.md` の受入条件と ADR-0001/0002 の決定事項をチェックリスト化する。
   - backend と infra の責任分界（実装対象/参照対象）を確定する。
2. backend 観測性改修
   - `pom.xml` に必要依存を追加する。
   - Appender 初期化処理を追加し、失敗時ログを実装する。
   - `RequestLoggingContextFilter` 周辺を `trace_id` / `span_id` 主体へ整合させる。
3. テスト改修・検証
   - logging/telemetry 関連テストを更新し、形式・主従関係・上書き禁止を検証する。
   - 変更近傍テスト→必要に応じて backend 範囲拡大で実行する。
4. 運用受入確認
   - Datadog 上の双方向遷移とタグ整合（`service` / `env` / `version` + 補助タグ）を確認する。
   - Datadog 側 remapper/preprocess 手順の適用状態を運用担当と突合する。
5. ドキュメントと完了判定
   - `tasks.md` を本計画に基づき作成し、実装タスクへ分解する。
   - 受入条件充足と未確認事項を明示してクローズする。

## 未解決事項
- Datadog Site（`datadoghq.com` / `ap1.datadoghq.com` など）の環境適用ポリシー最終決定。
- Datadog Preprocessing/Trace ID remapper の本番運用手順と責任主体（backend/infra/運用）の最終合意。
- monitor / dashboard / index 設計の責任分界と実装順序の最終確定。
- `OTEL_SERVICE_NAME` と `DD_SERVICE` を将来 `<service-name>` 共通変数へ完全統一する移行タイミング。
