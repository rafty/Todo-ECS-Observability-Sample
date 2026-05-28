# infra

このディレクトリは AWS CDK（TypeScript）でインフラを定義する領域です。

## 前提

- Node.js
- AWS CLI
- AWS CDK v2
- Docker（`cdk synth/diff/deploy` 時の backend イメージビルドに必要）
- 対象アカウントへアクセス可能な AWS 認証情報

## 主要コマンド

`infra/` 直下で実行します。

```bash
npm ci
npm run build
npm test -- --runInBand
npx cdk synth -c env=prod
npx cdk diff -c env=prod
npx cdk deploy -c env=prod
```

## 環境切替ルール

- 環境は `-c env=<dev|stg|prod>` で指定する。
- 環境値（`accountId` / `region` / Datadog 設定）は `lib/config/environment-config.ts` で管理する。

## 現行 O11y 構成（このブランチの要点）

### ECS タスク追加コンテナ

- `LogRouterContainer`（FireLens / Fluent Bit）
- `DatadogAgentContainer`（Datadog Agent sidecar）

### ログ経路

- `TodoBackendContainer` は `awsfirelens` を使い、app ログを Datadog Logs へ送る。
- `LogRouterContainer` / `DatadogAgentContainer` は `awslogs` を使い、診断ログを CloudWatch Logs へ送る。

### traces / metrics 経路

- trace: app -> OTLP/gRPC `4317` -> Datadog Agent -> Datadog APM
- metrics: app(Micrometer) -> OTLP/HTTP `4318` -> Datadog Agent -> Datadog Metrics

### CDK で注入する主要環境変数

- app: `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`, `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT`, `MANAGEMENT_OTLP_METRICS_EXPORT_URL`, `OTEL_*`, `DD_SERVICE`, `DD_ENV`, `DD_VERSION`
- agent: `DD_API_KEY`（Secret 参照）, `DD_SITE`, `DD_APM_ENABLED`, `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT`, `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT`, `DD_SERVICE`, `DD_ENV`, `DD_VERSION`, `DD_TAGS`

### シークレット

- Datadog API Key: `/<env>/todo-backend/datadog/api-key`
- DB 接続情報: `/todo/<env>/backend/database`

## 実行時の注意

- `frontend` 更新時は `infra` 実行前に `frontend/` で `npm run build` を実行する。
- `cdk deploy` は backend イメージ配布（ECR）とインフラ更新を同時に行う。

## 関連ドキュメント

- [docs 入口](../docs/README.md)
- [Observability 仕様](../docs/infra/o11y.md)
- [AWS デプロイ手順（Monorepo 全体）](../docs/development/aws-deployment-manual.md)
- [ADR ディレクトリ](../docs/adr/)
