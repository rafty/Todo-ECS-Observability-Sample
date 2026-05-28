# AWS デプロイ手順（Monorepo 全体）

## この手順の対象

- `frontend/`・`backend/`・`infra/` で構成された本モノレポを AWS へデプロイする
- ローカル開発手順ではなく、AWS へのビルド/配備手順を扱う

## 先に把握しておくこと

- 実行順は `frontend build -> cdk synth/diff -> cdk deploy` です。
- `cdk deploy` 時に以下が同時に行われます。
  - backend Docker イメージの ECR 配布
  - CloudFormation によるインフラ更新
  - `frontend/dist` と `runtime-config.json` の S3 配備
- `infra/lib/infra-stack.ts` は `frontend/dist` を必須とするため、先に frontend ビルドが必要です。

## 配備フロー

```mermaid
flowchart LR
  A[frontend build] --> B[cdk synth / diff]
  B --> C[cdk deploy]
  C --> D[ECR image push]
  C --> E[CloudFormation update]
  C --> F[S3 deploy + CloudFront invalidation]
```

## 0. 前提条件

- AWS 認証情報が対象アカウントで利用可能（必要に応じて AssumeRole）
- Node.js / npm / AWS CLI / AWS CDK v2 が利用可能
- ローカルのコンテナランタイムが起動済み（Docker Desktop など）
- 環境指定は `-c env=<dev|stg|prod>` を必ず付与

## 0.1 環境設定の確認

デプロイ先の account/region は `infra/lib/config/environment-config.ts` で管理します。  
実行前に、対象環境（例: `prod`）の値が意図した AWS アカウント/リージョンであることを確認してください。

## 0.2 Datadog API Key Secret の事前作成

- Secret 名は `/<environment>/<service>/datadog/api-key` 形式に統一します。
  - 例: `/prod/todo-backend/datadog/api-key`
- 値は `DD_API_KEY` のみを格納し、平文で Git 管理しません。
- ローテーションは年1回以上の手動実施とし、責任者は当該 Secret へのアクセス権限を持つ担当者とします。

```bash
aws secretsmanager create-secret \
  --name /prod/todo-backend/datadog/api-key \
  --secret-string '<DATADOG_API_KEY>'
```

更新時:

```bash
aws secretsmanager put-secret-value \
  --secret-id /prod/todo-backend/datadog/api-key \
  --secret-string '<DATADOG_API_KEY>'
```

## 0.3 Datadog Application Key（任意: API 検証自動化時）

Datadog API（Logs Search API など）で到達確認を自動化する場合は、Application Key も Secrets Manager に登録する。
この Secret は CDK デプロイ時に自動注入されないため、検証スクリプトや手動確認で明示的に参照する。

```bash
aws secretsmanager create-secret \
  --name /prod/todo-backend/datadog/app-key \
  --secret-string '<DD_APPLICATION_KEY>'
```

更新時:

```bash
aws secretsmanager put-secret-value \
  --secret-id /prod/todo-backend/datadog/app-key \
  --secret-string '<DD_APPLICATION_KEY>'
```

## 1. 初回のみ: CDK Bootstrap

`infra/` で実行します。

```bash
cd infra
npx cdk bootstrap aws://<account-id>/<region> -c env=prod
```

- `<account-id>` と `<region>` は `environment-config.ts` の `prod` 定義に合わせます。
- `dev` / `stg` の場合は `-c env` と bootstrap 先を対応値に置き換えます。

## 2. frontend をビルド

```bash
cd frontend
npm ci
npm run build
```

確認:
- `frontend/dist` が生成されていること

## 3. CDK 事前確認（推奨）

```bash
cd infra
npm ci
npx cdk synth -c env=prod
npx cdk diff -c env=prod
```

## 4. デプロイ実行

```bash
cd infra
npx cdk deploy -c env=prod
```

## 5. デプロイ後の確認

CloudFormation 出力（または `cdk deploy` の出力）で以下を確認します。

- `TodoAppCloudFrontDomainName`
- `TodoAppCognitoHostedUiBaseUrl`
- `TodoAppCognitoUserPoolClientId`
- `TodoAppCognitoCallbackUrl`
- `TodoAppCognitoLogoutUrl`

確認ポイント:
- CloudFront ドメインで SPA が表示される
- Cognito Hosted UI でログインできる
- ログイン後に `/api/*` 経路で Todo API が利用できる

### 5.1 Datadog オブザーバビリティ確認

- Logs Explorer: `service:todo-backend env:prod @trace_id:* @span_id:*`
- Trace Explorer: `service:todo-backend env:prod`
- Metrics Explorer:
  - `sum:todo.operation.count{service:todo-backend,env:prod} by {operation,result}`
  - `avg:todo.operation.duration{service:todo-backend,env:prod} by {operation,result}`
- Error 追跡: `service:todo-backend env:prod status:error`
- CloudWatch Logs の sidecar ロググループ（`log_router` / `datadog-agent`）保持日数が `prod=14日` であること

### 5.2 Datadog 運用初期化チェック

- Logs index / exclusion / retention / daily quota を初期値で作成済みであること
- モニタ初期セット（error rate、latency p95/p99、Agent health、FireLens error、log volume anomaly）が有効化済みであること
- ダッシュボード初期セット（service overview、latency/throughput/errors、ECS task health、APM trace volume）が作成済みであること

## 6. 代表的な失敗ケース

### `frontend/dist` がない
- 症状: `cdk synth/diff/deploy` で asset 関連エラー
- 対応: `frontend` で `npm run build` を再実行

### コンテナランタイム未起動
- 症状: backend イメージのビルド/配布失敗
- 対応: Docker Desktop 等を起動して再実行

### AWS 権限不足
- 症状: `AccessDenied`、`sts:AssumeRole` 失敗、ECR push 失敗
- 対応: 利用プロファイルとロール権限を確認して再実行

## 関連

- [infra 入口 README](../../infra/README.md)
- [docs 入口](../README.md)
