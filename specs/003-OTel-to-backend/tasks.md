# Tasks: 003-OTel-to-backend

## 前提確認
- [x] T001 `AGENTS.md`（root / backend）、`backend/README.md`、`docs/README.md` を確認する
- [x] T002 `specs/003-OTel-to-backend/specs.md` の機能要件・受け入れ条件・制約を確認する
- [x] T003 `specs/003-OTel-to-backend/specs.md` に未確定事項セクションが存在しないことを確認し、確定方針のみで実装する
- [x] T004 `specs/003-OTel-to-backend/plan.md` の変更対象/非対象を確認する
- [x] T005 `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md` と `docs/adr/adr-0002-trace-correlation-otel-datadog.md` を実装基準として読み合わせる

## 実装タスク

### backend: 事前整理
- [x] T100 Spring 管理 Bean の計装対象 `public` メソッド一覧を作成する（Controller/Service/Logging/Security/Exception）
- [x] T101 span 対象外一覧を作成する（`private` メソッド、DTO/Entity の accessor、`main`、単純 record factory など）

### backend: OTel 導入方式の確定（PoC）
- [x] T110 OTel starter 方式の PoC 手順を作成する（依存、起動、trace/log 生成確認）
- [x] T111 OTel starter 方式の PoC を実施し、Spring Boot `4.0.5` 互換性を確認する（依存: T110）
- [x] T112 Java Agent 方式のフォールバック PoC 手順を作成する（依存: T110）
- [x] T113 OTel 導入方式を確定し、採用理由と不採用理由を記録する（依存: T111, T112）

### backend: 設定・依存の反映
- [x] T120 `backend/pom.xml` に OTel 関連依存を追加/調整する（依存: T113）
- [x] T121 `src/main/resources/application.properties` で `spring.application.name=todo-backend` を設定する（依存: T113）
- [x] T122 `application.properties` に `DD_SERVICE` / `DD_ENV` / `DD_VERSION` / `OTEL_*` の反映方針を追加する（依存: T121）
- [x] T123 `DD_ENV` を `dev|stg|prod` 前提で扱うための設定バリデーション方針を反映する（依存: T122）
- [x] T124 `DD_VERSION` を `backendDockerImageAsset.imageTag` 由来値で受け取る前提を設定に反映する（依存: T122）

### backend: ログ相関実装（ADR-0002 準拠）
- [x] T130 Logback 設定を更新し、JSON トップレベルに `trace_id` / `span_id` / `service` / `env` / `version` を出力する（依存: T120, T122）
- [x] T131 `trace_id` は 32 文字小文字 hex、`span_id` は 16 文字小文字 hex を前提に出力されることを実装で担保する（依存: T130）
- [x] T132 `RequestLoggingContextFilter` の `traceId` 取り扱いを見直し、`trace_id` の独自生成/上書きを禁止する（依存: T130）
- [x] T133 `X-Amzn-Trace-Id` を保持する場合は `x_amzn_trace_id`（または `aws_trace_id`）へ保存する（依存: T132）
- [x] T134 `traceId` キー名を新規ログ仕様から排除する（依存: T132, T133）

### backend: span 実装（全 public メソッド、Tracer API 併用）
- [x] T140 Tracer API 共通ヘルパー（または同等基盤）を実装する（依存: T120）
- [x] T141 Controller の対象 `public` メソッドへ span 実装を適用する（依存: T100, T101, T140）
- [x] T142 Service の対象 `public` メソッドへ span 実装を適用する（依存: T100, T101, T140）
- [x] T143 Logging/Security/Exception の対象 `public` メソッドへ span 実装を適用する（依存: T100, T101, T140）
- [x] T144 span 名称を業務意味ベース（`todo.*`）に統一する（依存: T141, T142, T143）
- [x] T145 span attributes/events へ機密情報を含めない制御を実装する（依存: T141, T142, T143）

### backend: metrics 実装
- [x] T150 業務 metrics（成功件数/失敗件数/処理時間）のメトリクス定義を作成する（依存: T120）
- [x] T151 Counter/Histogram を用途分離して実装する（依存: T150）
- [x] T152 metrics attributes を低カーディナリティ（`env/service/operation/result`）へ制限する（依存: T151）

## テスト / 検証タスク
- [x] T200 既存テストを更新し、`traceId` 非使用・`x_amzn_trace_id` 使用を検証する（依存: T133, T134）
- [x] T201 `trace_id` / `span_id` の存在と形式（32/16 小文字 hex）を検証するテストを追加する（依存: T131）
- [x] T202 全対象 `public` メソッドの span 生成を検証するテストを追加する（依存: T141, T142, T143, T144）
- [x] T203 metrics（Counter/Histogram、attributes 制約）を検証するテストを追加する（依存: T151, T152）
- [x] T204 機密情報が logs/spans/metrics に出力されないことを検証する（依存: T145, T152）
- [x] T205 既存 API 回帰（認証、CRUD、例外）に影響がないことを確認する（依存: T141, T142, T143）
- [x] T210 `./mvnw -DskipTests compile` を実行してビルド確認する（依存: T120-T152）
- [x] T211 `./mvnw test` を実行して回帰確認する（依存: T200-T205）
- [x] T212 Datadog 連携の手動確認手順を整理する（Logs/Trace 相関、service/env/version 一致）（依存: T211）
- [x] T213 実行できなかった検証項目があれば、理由と代替確認方法を記録する（依存: T212）

## ドキュメント更新タスク
- [x] T300 `backend/README.md` を更新し、OTel/Datadog 関連設定と検証手順を反映する（依存: T121, T122, T124）
- [x] T301 `docs/backend/logging.md` を更新し、`trace_id`/`span_id` 主相関と `x_amzn_trace_id` 補助方針を反映する（依存: T130-T134）
- [x] T302 `docs/development/backend-development.md` を更新し、ローカルでの OTel 検証手順を追加する（依存: T210, T211）
- [x] T303 最終ドキュメントに「全 `public` メソッド span 採用理由」を明記する（依存: T141-T145, T300-T302）
- [x] T304 最終ドキュメントの設定例が `application.properties` 形式であることを確認する（依存: T300-T302）
- [x] T305 ADR 差分を確認し、方針逸脱がある場合のみ `docs/adr/` を更新する（依存: T300-T304）

## 完了確認
- [x] T400 `specs.md` の受け入れ条件をチェックリストで満たしていることを確認する（依存: T213, T305）
- [x] T401 変更範囲が backend + 関連 docs に限定され、infra/frontend 実装変更が混在していないことを確認する（依存: T400）
- [x] T402 シークレット、認証情報、不要ファイルが差分に含まれていないことを確認する（依存: T401）
- [x] T403 変更内容・検証結果・未実施項目を最終報告用に整理する（依存: T402）
