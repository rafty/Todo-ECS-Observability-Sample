# Plan: 003-OTel-to-backend

## 実装方針
- 本 feature は **backend 単一領域の実装**を主体とし、infra と Datadog 設定は参照前提で進める。
- 実装判断は `specs/003-OTel-to-backend/specs.md` と、以下 ADR を基準に統一する。
  - `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`
  - `docs/adr/adr-0002-trace-correlation-otel-datadog.md`
- 実装は「互換性 PoC → ログ相関実装 → 全 public メソッド span 実装（Tracer API 併用）→ metrics 実装 → 検証」の順で段階実施する。
- 公開 API 契約・DB スキーマは変更せず、観測性のみ拡張する。

## 変更対象
- backend 実装:
  - `backend/pom.xml`（OTel 関連依存、必要に応じて AOP/Logback 拡張）
  - `backend/src/main/resources/application.properties`
    - `spring.application.name=todo-backend`
    - `DD_ENV`/`DD_SERVICE`/`DD_VERSION`/`OTEL_*` 前提の設定
  - `backend/src/main/resources/logback-spring.xml`（必要時に新規追加）
    - `trace_id`/`span_id`/`service`/`env`/`version` をトップレベル出力
  - `backend/src/main/java/com/example/backend/logging/RequestLoggingContextFilter.java`
    - `traceId` の扱い見直し、`x_amzn_trace_id` への整理
  - `backend/src/main/java/com/example/backend/` 配下の OTel 計装コード
    - 全 public メソッド span 方針を満たすための Tracer API 共通化（必要なら AOP を併用）
    - Controller/Service/その他 Spring 管理 Bean の public メソッド計装
  - `backend/src/test/java/com/example/backend/` 配下のテスト
    - `trace_id`/`span_id` 形式、`x_amzn_trace_id`、span 生成、機密情報非出力
- ドキュメント:
  - `backend/README.md`
  - `docs/backend/logging.md`
  - `docs/development/backend-development.md`
  - （必要なら）`docs/adr/` の追記・更新

## 変更しないもの
- `infra/` の ECS タスク定義、FireLens、Datadog Agent 実装（`specs/004-*` 管轄）。
- Datadog Organization 側の運用設定実装（pipeline/index/monitor 作成自体）。
- `frontend/` 実装。
- Todo API のリソース構造、入出力契約、DB スキーマ/Flyway。

## 技術方針
- 既存パターン再利用:
  - `SLF4J + Logback` 継続。
  - 既存の構造化ログ、Filter、Controller/Service/Repository 分離を維持。
- OTel 導入方式:
  - 先に Spring Boot `4.0.5` との互換性 PoC を実施。
  - PoC で問題なければ starter 方式、問題があれば Java Agent 方式へ切替（spec 制約準拠）。
- トレース相関方式（ADR-0002 準拠）:
  - 主相関キーは `trace_id`/`span_id`。
  - `traceId` キー名は新規仕様で使用しない。
  - `X-Amzn-Trace-Id` は `x_amzn_trace_id`（または `aws_trace_id`）で補助保持。
- 全 public メソッド span 方針:
  - 対象は Spring 管理 Bean の public メソッドを基準とする。
  - Tracer API 併用を標準とし、共通ヘルパー/Aspect で重複を削減する。
  - self-invocation 等で AOP 適用外となる経路は、必要に応じて明示的 Tracer API 呼び出しで補完する。
- 新規設定/依存:
  - OTel/Logback/AOP に必要な依存のみ追加し、不要なライブラリ増加は避ける。
  - `DD_VERSION` は `infra/lib/constructs/backend-image-deployment-construct.ts` の `backendDockerImageAsset.imageTag` を採用する前提で受ける。

## データや契約への影響
- DB スキーマ: 変更なし。
- API 契約: 変更なし（レスポンス/ステータス/エンドポイント不変）。
- イベント契約: 該当なし。
- 環境変数:
  - 追加/利用強化: `DD_SERVICE`, `DD_ENV`, `DD_VERSION`, `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES`, `OTEL_EXPORTER_OTLP_*`。
  - 既存 `SPRING_*` 設定と共存。
- Secret / 設定値:
  - backend 側で新規 secret は保持しない（Datadog API Key は infra 管理）。
- デプロイ/運用:
  - ログフィールド変更（`traceId` -> `x_amzn_trace_id`）は運用クエリ更新が必要。
  - Datadog 側で `trace_id` の予約属性認識確認が必要。

## リスク
- 互換性リスク:
  - OTel starter と Spring Boot `4.0.5` の組み合わせで起動/計装不整合が起こる可能性。
- 実装リスク:
  - 全 public メソッド計装により span 数が増加し、APM コスト/ノイズが増える可能性。
  - AOP 適用境界（proxy/self-invocation）で計装漏れが起きる可能性。
- 運用リスク:
  - Datadog 側で `trace_id` が予約属性として認識されない場合、相関が成立しない。
- セキュリティリスク:
  - span attributes/log fields へ機密情報が混入する可能性。

## 検証方針
- ローカル/CI 基本検証（backend）:
  - `./mvnw -DskipTests compile`
  - `./mvnw test`
- テスト観点:
  - `trace_id`/`span_id` の存在と形式（32/16 小文字 hex）
  - `traceId` 非使用、`x_amzn_trace_id` 使用
  - 全対象 public メソッドの span 生成
  - 機密情報非出力（ログ/span/metrics）
  - 既存 API 回帰（認証、CRUD、例外ハンドリング）
- 結合観点（infra 連携前提）:
  - Datadog Logs で `trace_id`/`span_id` が検索可能
  - APM Trace から Logs、Logs から Trace への遷移成立
  - `service/env/version` の logs/traces 一致

## ドキュメント更新方針
- 更新対象:
  - `backend/README.md`（環境変数、実行/検証観点）
  - `docs/backend/logging.md`（`trace_id`/`span_id`/`x_amzn_trace_id` と非出力ルール）
  - `docs/development/backend-development.md`（開発時の OTel 確認手順）
- ADR:
  - 既存 `ADR-0002` を前提に実装する。
  - 実装で方針差分が出る場合のみ `docs/adr/` を更新する。

## 実施順序
1. 事前確認:
   - `specs.md` と `ADR-0001/0002` を実装チェックリスト化する。
   - 計装対象 public メソッドの一覧化（Spring 管理 Bean 単位）。
2. 互換性 PoC:
   - OTel 導入方式を PoC し、starter/agent の採用経路を確定する。
3. 基盤設定変更:
   - `pom.xml`、`application.properties`、（必要なら）`logback-spring.xml` を更新。
   - `spring.application.name=todo-backend` と tagging 設定を反映。
4. トレース相関実装:
   - `trace_id`/`span_id` 出力、`x_amzn_trace_id` への整理、`traceId` 廃止を実装。
5. span/metrics 実装:
   - 全対象 public メソッド計装（Tracer API 併用）を適用。
   - 業務 metrics（Counter/Histogram）を追加。
6. テスト拡充:
   - 既存テスト更新 + 新規テスト追加で受け入れ条件を検証。
7. ドキュメント更新:
   - README/docs を実装結果に同期。
8. 総合確認:
   - backend テスト実行、差分レビュー、未対応事項の明文化。

## 未解決事項
- OTel 導入の最終方式（starter or Java Agent）は PoC 結果で確定する。
- Datadog 側 remap/preprocessing の最終設定値は `specs/004-*` 実装と整合確認が必要。
- 全 public メソッド計装の「対象一覧」（どこまでを業務上有効な対象とするか）は、実装前に明示リスト化して確定する。
