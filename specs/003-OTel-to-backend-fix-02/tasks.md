# Tasks: OTel-to-backend fix-02（Datadog タグ戦略・ログ相関修正）

## 前提確認
- [x] T001 `AGENTS.md`（root）、`README.md`、`docs/README.md`、`docs/AGENTS.md` を確認し、文書更新ルールと責任分界を整理する
- [x] T002 `specs/003-OTel-to-backend-fix-02/specs.md` の機能要件（`REQ-SPEC-FIX02-001`〜`005`）と受け入れ条件をチェックリスト化する
- [x] T003 `specs/003-OTel-to-backend-fix-02/plan.md` の変更対象/非対象を確認し、backend 変更範囲を明確化する
- [x] T004 `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md` の OTLP/gRPC・Unified Service Tagging 方針を実装基準として確認する
- [x] T005 `docs/adr/adr-0002-trace-correlation-otel-datadog.md` の `trace_id` / `span_id` 正規化方針と `traceId` 非採用方針を確認する

## 実装タスク

### backend: 依存関係・起動初期化
- [x] T100 `backend/pom.xml` に `opentelemetry-logback-appender-1.0` を追加し、既存依存との競合がないことを確認する（依存: T002, T003）
- [x] T101 `backend/src/main/java/com/example/backend/telemetry/`（または既存設定レイヤ）に `OpenTelemetryAppender.install(openTelemetry)` の起動初期化処理を追加する（依存: T100）
- [x] T102 Appender 初期化失敗時に原因追跡可能なログを出力し、起動可否ポリシー（劣化運転）を既存方針に合わせて実装する（依存: T101）

### backend: ログ相関キー整合（ADR-0002 準拠）
- [x] T110 `RequestLoggingContextFilter` および関連ロギング処理を見直し、`trace_id` / `span_id` の独自生成・上書きを禁止する（依存: T005）
- [x] T111 `X-Amzn-Trace-Id` の扱いを補助情報（`x_amzn_trace_id`）に限定し、主相関キーとして扱わないよう整合する（依存: T110）
- [x] T112 新規仕様・新規実装で `traceId` キー名を使用しないことをコード/設定/テスト観点で担保する（依存: T110, T111）
- [x] T113 JSON ログのトップレベルに `trace_id` / `span_id` が出力される設定を確認し、既存ログフォーマットに最小差分で反映する（依存: T101, T110）

### backend: タグ契約整合（ADR-0001 / infra 契約）
- [x] T120 `application.properties` と起動設定の扱いを確認し、`OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317` 前提を崩さないことを明示する（依存: T004）
- [x] T121 backend 側の `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES`（`deployment.environment`、`service.version`）が `DD_SERVICE` / `DD_ENV` / `DD_VERSION` と矛盾しない契約を実装・設定で担保する（依存: T120）
- [x] T122 `DD_VERSION` / `service.version` の単一ソースを `infra/lib/constructs/backend-image-deployment-construct.ts` の `backendDockerImageAsset.imageTag` とする前提を backend 側検証観点へ反映する（依存: T121）
- [x] T123 補助タグ（`team` / `aws_account` / `system`）は infra 注入前提であることを維持し、backend 側で重複注入や契約逸脱を発生させない（依存: T121）

## テスト / 検証タスク
- [x] T200 `backend/src/test/java/com/example/backend/logging/` の関連テストを更新し、`trace_id` / `span_id` の存在と形式（32/16 小文字 hex）を検証する（依存: T113）
- [x] T201 `RequestLoggingContextFilter` のテストを更新し、`trace_id` / `span_id` を上書きしないこと、`x_amzn_trace_id` が補助情報であることを検証する（依存: T110, T111）
- [x] T202 telemetry 初期化のテストを追加/更新し、Appender 初期化処理と失敗時ログ出力を検証する（依存: T101, T102）
- [x] T203 変更近傍の最小テスト（logging/telemetry/controllers）を優先実行し、必要に応じて backend テスト範囲を拡大する（依存: T200, T201, T202）
- [x] T204 実運用確認手順を整理し、Datadog の Logs/Trace Explorer で双方向遷移と `service` / `env` / `version` 整合を確認する（依存: T121, T122, T203）
- [x] T205 実施できない検証（Datadog remapper/preprocess 運用など）があれば、未確認事項と理由を記録する（依存: T204）

## ドキュメント更新タスク
- [x] T300 実装結果が `specs/003-OTel-to-backend-fix-02/specs.md` の受け入れ条件に影響する場合、該当セクションの更新要否を確認する（依存: T203）
- [x] T301 実装方針変更が発生した場合のみ `docs/adr/adr-0001` / `adr-0002` の更新要否を確認し、方針逸脱時に限って ADR 更新を実施する（依存: T203, T204）
- [x] T302 backend の起動方法・主要コマンド・検証手順に変更がある場合のみ `backend/README.md` 更新を実施する（依存: T203）

## 完了確認
- [x] T400 `REQ-SPEC-FIX02-001`〜`005` の充足を確認し、受け入れ条件チェックリストを完了する（依存: T205, T300）
- [x] T401 変更が backend 観測性レイヤ（ログ相関・設定・初期化・テスト）に限定され、対象外（infra 実装、API/DB/認証仕様）へ波及していないことを確認する（依存: T400）
- [x] T402 差分にシークレット・認証情報・不要ファイル（`.DS_Store` など）が含まれていないことを確認する（依存: T401）
- [x] T403 最終報告として、実施済み検証・未確認範囲・運用引き継ぎ事項（Datadog remapper/preprocess 責任分界）を明記する（依存: T402）

## 実施メモ（T205 / T403）
- `JAVA_HOME=$(/usr/libexec/java_home -v 21)` 設定後に `mvn -DskipTests compile` が `BUILD SUCCESS` となることを確認済み。
- `JAVA_HOME=$(/usr/libexec/java_home -v 21)` 設定のうえ `mvn -Dtest=OpenTelemetryLogbackAppenderInitializerTest test` を実行し、対象テスト（2件）が `BUILD SUCCESS` となることを確認済み。
- Datadog 側の remapper/preprocess、Trace↔Logs 双方向遷移確認は AWS/Datadog 実環境での運用検証が必要。
