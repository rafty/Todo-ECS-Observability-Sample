# Todo Application on AWS ECS with Observability

本リポジトリは、[rafty/Todo-ECS-Sample](https://github.com/rafty/Todo-ECS-Sample.git) をベースに、Datadog 連携を含む Observability 構成を追加したサンプルです。

## システム概要

```mermaid
flowchart LR
  U[Browser] --> CF[CloudFront]
  CF -->|default| S3[S3 Frontend]
  CF -->|/api/*| ALB[ALB]
  U --> COG[Cognito Hosted UI]

  subgraph TASK[ECS Fargate Task]
    APP["TodoBackendContainer\nSpring Boot + OpenTelemetry Java Agent"]
    FL["LogRouterContainer\nFireLens / Fluent Bit"]
    AG["DatadogAgentContainer\nOTLP Receiver"]
  end

  ALB --> APP
  APP --> DB[(Aurora PostgreSQL)]
  APP --> SM[Secrets Manager]
  APP -->|stdout / stderr JSON logs| FL
  APP -->|OTLP traces gRPC 4317| AG
  APP -->|OTLP metrics HTTP 4318| AG
  FL -->|Datadog Logs output| DDLOG[Datadog Logs]
  AG -->|traces| DDAPM[Datadog APM]
  AG -->|metrics| DDMET[Datadog Metrics]
  FL -->|sidecar diagnostics| CW[CloudWatch Logs]
  AG -->|sidecar diagnostics| CW
```

このサンプルでは Datadog を `logs / traces / metrics` の主な調査画面にし、CloudWatch Logs は ECS task 内 sidecar の診断ログを短期保持する用途に限定します。`TodoBackendContainer` のアプリログは `awsfirelens` で FireLens に渡すため、CloudWatch Logs へ直接は出力しません。

O11y コンポーネントは責務を分けています。

- `OpenTelemetry Java Agent`: アプリ JVM に attach し、Spring Web MVC / JDBC / JVM Runtime / Micrometer を自動計装して traces / metrics を生成する。ログ配送は担当しない。
- `DatadogAgentContainer`: 同一 ECS task 内で OTLP receiver として動作し、Java Agent から受けた traces / metrics を Datadog APM / Metrics へ送る。
- `LogRouterContainer`（FireLens / Fluent Bit）: アプリの stdout / stderr JSON ログを Datadog Logs へ送る。ログ配送を trace / metrics 経路から分離し、OTLP logs は使わない。
- `CloudWatch Logs`: `log_router` / `datadog-agent` の内部ログ、転送エラー、OTLP 受信/送信エラーの確認先。ECS Service / Task の状態変化は ECS の control plane 側イベントとして扱い、アプリログの保管先とは分ける。

## 最初に読むドキュメント

1. 全体入口: [docs/README.md](./docs/README.md)
2. AWS デプロイ手順: [docs/development/aws-deployment-manual.md](./docs/development/aws-deployment-manual.md)
3. サブプロジェクト入口:
   - [backend/README.md](./backend/README.md)
   - [frontend/README.md](./frontend/README.md)
   - [infra/README.md](./infra/README.md)
4. Observability 仕様:
   - [docs/infra/o11y.md](./docs/infra/o11y.md)
   - [docs/backend/logging.md](./docs/backend/logging.md)
   - [docs/adr/](./docs/adr/)
5. 負荷テスト関連:
   - [DLT デプロイ手順](./docs/load-test/load-test-deployment.md)
   - [負荷テスト実行手順（Cognito/JWT/K6/DLT）](./docs/load-test/load-test-operations.md)

## ディレクトリ構成

| ディレクトリ | 役割 |
| --- | --- |
| `backend/` | Spring Boot API（`/api/todos`） |
| `frontend/` | React + Vite の SPA |
| `infra/` | AWS CDK（TypeScript）によるインフラ定義 |
| `docs/` | 詳細ドキュメントと ADR |

## 前提条件

- Node.js（frontend / infra の要件を満たす版）
- Java 21（backend）
- Docker
- AWS CLI
- AWS CDK v2
- 対象アカウントで利用可能な AWS 認証情報

## クイックデプロイ（prod 例）

### 1. frontend build

```bash
cd frontend
npm ci
npm run build
```

### 2. Datadog API Key Secret 作成（初回）

```bash
aws secretsmanager create-secret \
  --name /prod/todo-backend/datadog/api-key \
  --secret-string '<DATADOG_API_KEY>'
```

### 3. cdk deploy

```bash
cd ../infra
npm ci
npx cdk synth -c env=prod
npx cdk diff -c env=prod
npx cdk deploy -c env=prod
```

## デプロイ後のアクセス

CloudFormation 出力 `TodoAppCloudFrontDomainName` を確認し、`https://<TodoAppCloudFrontDomainName>/` にアクセスします。

## 主要 ADR

- [ADR-0001: ECS Fargate 上の Spring Boot アプリにおける Datadog / OpenTelemetry / ログ収集方式](./docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md)
- [ADR-0002: OpenTelemetry `trace_id` / `span_id` を正とする Datadog ログ相関方式](./docs/adr/adr-0002-trace-correlation-otel-datadog.md)
- [ADR-0003: Datadog Agent / FireLens / Spring Boot O11y 設定方針](./docs/adr/adr-0003-OTel-DatadogAgent-settings.md)
- [ADR-0004: APMエージェントの選定（OpenTelemetry Java Agent）](./docs/adr/adr-0004-OTel-Java-Agent.md)
