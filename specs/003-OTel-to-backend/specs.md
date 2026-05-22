# Spec: 003-OTel-to-backend

## 概要
- `backend/` の Spring Boot アプリに OpenTelemetry ベースの計装を導入し、Datadog で `logs / traces / metrics` を横断して調査できる状態を定義する。
- 本仕様は backend 側（アプリコード・設定・テスト観点）を対象とし、ECS タスク定義・FireLens・Datadog Agent の実装は `specs/004-Datadog-agent-to-cdk` を前提とする。

## 背景
- 現在の backend は構造化 JSON ログを stdout に出力し、主に CloudWatch Logs で調査する構成である。
- 障害解析・性能解析を Datadog 中心に統一するには、ログ単体ではなく trace と metrics を併せた相関運用が必要である。
- 既存の `SLF4J + Logback`、Controller/Service/Repository の責務分離を維持したまま、変更範囲を backend に閉じて拡張する必要がある。

## 目的
- Datadog Logs で backend ログを検索できること。
- Datadog APM で自動計装 + 業務 span を確認できること。
- Datadog Metrics で業務成功/失敗件数、処理時間を確認できること。
- `env / service / version` で `logs / traces / metrics` を横断できること。
- 機密情報非出力・高カーディナリティ抑制を満たすこと。

## スコープ
- 変更主体: `backend/`
  - OTel 導入（依存関係・設定・初期化方針）
  - 全 `public` メソッド span 付与の設計・実装方針（Tracer API 併用）
  - 業務 metrics の設計・実装方針
  - 構造化ログと trace 相関要件
  - backend テスト方針・受け入れ観点
  - 全 `public` メソッド span 採用理由を最終ドキュメントへ記載する要件
- 参照のみ（変更は別仕様）:
  - `infra/`（Datadog Agent sidecar / FireLens / タスク定義）
  - Datadog 組織設定（Site / Organization / API Key / Application Key / 権限スコープ。詳細は `specs/004-Datadog-agent-to-cdk/specs-draft.md` で管理）
- 領域区分:
  - 実装変更は backend 単一領域を原則とする。
  - ただし成立条件として infra と Datadog 設定に依存するため、受け入れ確認は複数領域連携を前提とする。

## 対象外
- ECS タスク定義変更、FireLens 設定、Datadog Agent コンテナ設定。
- CloudWatch Logs 保持戦略の最終決定。
- Datadog ダッシュボード/モニターの詳細設計。
- OpenTelemetry Logs API へのログ実装置き換え。

## ユーザーストーリー / 利用シナリオ
- 運用担当者は Datadog APM の遅延 trace から該当ログへ遷移し、失敗原因を短時間で特定できる。
- 開発者は業務処理（Todo 作成・更新・削除）の span と metrics を確認し、回帰や性能劣化を検知できる。
- セキュリティ/監査担当者は、ログ・span・metrics に機密情報が出力されていないことを確認できる。

## 機能要件
- FR-BE-LOG-001: 業務コードのログ API は `SLF4J` を使用し、`System.out.println` や `printStackTrace` を使用しない。
- FR-BE-LOG-002: 本番ログは JSON 形式で stdout 出力する（既存 `logging.structured.format.console=logstash` 方針を継続）。
- FR-BE-LOG-003: Datadog 相関に必要な属性をログのトップレベルに含める。
  - 必須属性: `service`, `env`, `version`, `trace_id`, `span_id`
  - `trace_id`: OpenTelemetry `SpanContext.traceId`（32 文字小文字 hex）
  - `span_id`: OpenTelemetry `SpanContext.spanId`（16 文字小文字 hex）
  - Datadog 側で自動認識されない場合は、Preprocessing for JSON logs または Trace ID remapper で `trace_id` を予約 Trace ID へマッピングする。
- FR-BE-LOG-004: 既存のリクエスト相関キー（`requestId`, `path`, `httpMethod` など）は維持する。`X-Amzn-Trace-Id` を保持する場合は `x_amzn_trace_id`（または `aws_trace_id`）を使用し、`traceId` というキー名は新規仕様で使用しない。
- FR-BE-LOG-005: 機密情報（password、token、API key、Authorization 全文、Cookie 全文、PII 生値等）をログに出力しない。
- FR-BE-TRACE-001: backend に OTel 計装を導入し、HTTP/DB/外部 HTTP の自動計装を Datadog APM で確認可能にする。
- FR-BE-TRACE-002: 本リポジトリの Todo アプリはサンプルかつメソッド数が少ないため、backend の対象 `public` メソッドすべてに span を付与する。
- FR-BE-TRACE-003: 手動 span の実装手段は Tracer API 併用を標準とする。`@WithSpan` は補助的に利用してよい。
- FR-BE-TRACE-004: span 名は業務意味を表す命名に統一する（例: `todo.create`, `todo.update`, `todo.delete`）。
- FR-BE-TRACE-005: span attributes / events は低カーディナリティかつ機密情報非含有を必須とする。
- FR-BE-METRIC-001: 業務メトリクス（成功件数、失敗件数、処理時間）を OTel metrics で出力する。
- FR-BE-METRIC-002: metrics attributes は `env/service/operation/result` 等の低カーディナリティに限定する。
- FR-BE-METRIC-003: metrics は `Counter` と `Histogram` を用途分離し、単位と説明を付与する。
- FR-BE-TAG-001: Unified Service Tagging は次で固定する。
  - `DD_SERVICE=todo-backend`
  - `DD_ENV` は `dev | stg | prod`
  - `DD_VERSION` は `infra/lib/constructs/backend-image-deployment-construct.ts` の `backendDockerImageAsset.imageTag` を採用する（実体は `BackendImageDeploymentConstruct.imageTag` で受け渡す）。
  - `OTEL_SERVICE_NAME=todo-backend`
  - `OTEL_RESOURCE_ATTRIBUTES` の `service.name=todo-backend`, `deployment.environment=<DD_ENV>`, `service.version=<DD_VERSION>` を一致させる。
- FR-BE-TAG-002: `spring.application.name` は `todo-backend` に統一する。
- FR-BE-DOC-001: 最終ドキュメントには「Todo アプリはサンプルでメソッド数が少ないため、全 `public` メソッドに span を付与する方針を採用した」旨を明記する。
- FR-BE-DOC-002: 最終ドキュメントには、トレース相関の正を `trace_id` / `span_id` とし、`X-Amzn-Trace-Id` は補助フィールド（`x_amzn_trace_id`）として扱う方針を明記する。
- FR-BE-DOC-003: 設定例は `application.properties` 形式で記載する。

## 非機能要件
- 運用性:
  - Datadog 上で `service:todo-backend env:<env>` を基準に横断調査できること。
  - Trace から関連ログに遷移できること。
- 保守性:
  - 計装追加により業務コードが過度に肥大化しないこと（共通化・最小実装）。
- セキュリティ:
  - 機密情報を telemetry（logs/traces/metrics）に含めないこと。
- 性能:
  - OTel 導入後も API 応答性能に重大な劣化を発生させないこと（閾値は別途定義）。
- コスト:
  - 高カーディナリティ属性を抑制し、Datadog 課金増を制御可能であること。

## 受け入れ条件
- backend ビルド/テストが通過すること（少なくとも変更箇所近傍テスト + 起動確認）。
- Datadog Logs で backend ログが検索できること。
- Datadog Logs のログイベントに `trace_id` / `span_id` が存在し、`trace_id` は 32 文字小文字 hex、`span_id` は 16 文字小文字 hex であること。
- Datadog APM で HTTP/DB の自動計装 span を確認できること。
- backend の対象 `public` メソッドすべてに対応する span を確認できること。
- 実装で Tracer API を併用していること。
- Datadog Metrics で業務成功/失敗件数、処理時間を確認できること。
- 機密情報がログ・span attributes・metrics attributes に含まれないこと。
- 最終ドキュメントに、全 `public` メソッド span 採用理由（サンプルアプリでありメソッド数が少ないこと）が記載されていること。
- 最終ドキュメントに、`trace_id` / `span_id` を主相関キーとし、`X-Amzn-Trace-Id` は補助フィールド扱いであることが記載されていること。
- `spring.application.name=todo-backend` が設定され、`DD_SERVICE=todo-backend` と一致していること。
- `DD_ENV` が `dev|stg|prod` のいずれかで運用されること。
- `DD_VERSION` が `backendDockerImageAsset.imageTag` 由来値で設定されること。

## 制約
- backend 既存構成（Spring Boot 4.0.x、`application.properties`、構造化ログ）に整合すること。
- 公開 API 契約（`/api/todos`）は変更しないこと。
- `SLF4J + Logback` は維持し、ログ API を別方式へ置き換えないこと。
- infra 側の OTLP 受信設定、FireLens 経路が有効であることを前提とする。
- シークレットはコード埋め込み禁止。環境変数/Secrets Manager 経由で受け取ること。
- OpenTelemetry Spring Boot Starter の Spring Boot `4.0.5` 互換性は PoC で確認すること。互換性問題がある場合は Java Agent 方式へ切り替える。
- 「全メソッド」span の対象は `public` メソッドのみとし、`private` メソッド・自己呼び出しは対象外とする。

## 依存関係
- `specs/004-Datadog-agent-to-cdk/specs-draft.md`（infra 側実装）
- `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`
- Datadog 組織設定（ログパイプライン、予約属性 remap、index/retention。詳細は `specs/004-Datadog-agent-to-cdk/specs-draft.md` で管理）
- ECS 実行環境での環境変数注入（`DD_*`, `OTEL_*`）
- `infra/lib/constructs/backend-image-deployment-construct.ts`（`DD_VERSION` の採番元）
- `docs/adr/adr-0002-trace-correlation-otel-datadog.md`（トレース相関方針）
