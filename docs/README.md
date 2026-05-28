# docs

このディレクトリは、リポジトリ全体の詳細ドキュメント入口です。

## まずどこを読むか

| 知りたいこと | 参照先 |
| --- | --- |
| AWS へデプロイする手順 | [development/aws-deployment-manual.md](./development/aws-deployment-manual.md) |
| backend のローカル開発手順 | [development/backend-development.md](./development/backend-development.md) |
| backend のログ設計 | [backend/logging.md](./backend/logging.md) |
| Observability（logs / metrics / trace の経路と責務） | [infra/o11y.md](./infra/o11y.md) |
| frontend の認証・runtime 設定 | [frontend/README.md](./frontend/README.md) |
| 負荷テスト環境（DLT）デプロイ手順 | [load-test/load-test-deployment.md](./load-test/load-test-deployment.md) |
| 負荷テスト実行手順（Cognito/JWT/K6/DLT） | [load-test/load-test-operations.md](./load-test/load-test-operations.md) |
| 設計判断（ADR） | [adr/](./adr/) |

## 構成

```mermaid
flowchart TB
  ROOT[docs/README.md]
  B[backend/]
  F[frontend/]
  I[infra/]
  L[load-test/]
  D[development/]
  A[adr/]

  ROOT --> B
  ROOT --> F
  ROOT --> I
  ROOT --> L
  ROOT --> D
  ROOT --> A
```

## ディレクトリ別リンク

### backend

- [backend ドキュメント入口](./backend/README.md)
- [ログ設計](./backend/logging.md)

### frontend

- [frontend ドキュメント入口](./frontend/README.md)

### infra

- [Observability 仕様（FireLens / Datadog Agent / OTLP）](./infra/o11y.md)

### development

- [backend 開発手順](./development/backend-development.md)
- [AWS デプロイ手順（Monorepo 全体）](./development/aws-deployment-manual.md)

### load-test

- [負荷テスト環境（DLT）デプロイ手順](./load-test/load-test-deployment.md)
- [負荷テスト実行手順（Cognito/JWT/K6/DLT）](./load-test/load-test-operations.md)
- [DLT コンソールで Scenario 作成・実行（画面キャプチャ）](./load-test/dlt-new-scenario.md)
- [DLT テストシナリオ 実行結果確認（画面キャプチャ）](./load-test/dlt-test-result.md)

### adr

- [ADR-0001: ECS Fargate 上の Spring Boot アプリにおける Datadog / OpenTelemetry / ログ収集方式](./adr/adr-0001-o11y-datadog-otel-ecs-fargate.md)
- [ADR-0002: OpenTelemetry `trace_id` / `span_id` を正とする Datadog ログ相関方式](./adr/adr-0002-trace-correlation-otel-datadog.md)
- [ADR-0003: Datadog Agent / FireLens / Spring Boot O11y 設定方針](./adr/adr-0003-OTel-DatadogAgent-settings.md)

## 更新時のルール（要約）

- 入口情報は README に、詳細は各サブディレクトリに配置する。
- 文書を追加したら、この `docs/README.md` から辿れるようにリンクを追加する。
- 仕様変更時は、関連する README と `docs/` の両方を更新対象として確認する。
