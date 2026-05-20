# infra

このディレクトリは AWS CDK（TypeScript）でインフラを定義する領域です。

## 前提
- Node.js（本リポジトリの推奨バージョンに従う）
- AWS CLI
- AWS CDK v2
- AWS認証情報（対象アカウントへ AssumeRole 可能な状態）

## 主要コマンド
`infra/` 直下で実行します。

```bash
npm install
npm run build
npm test -- --runInBand
npx cdk synth -c env=prod
npx cdk diff -c env=prod
```

## 環境切替ルール
- 環境は `-c env=<dev|stg|prod>` で指定します。
- 未指定または不正値の場合、`bin/infra.ts` でエラー終了します（誤デプロイ防止）。
- 環境値（`accountId` / `region`）は `lib/config/environment-config.ts` で管理します。

## 002-create_network で追加された内容
- 新規VPC（デフォルトVPC不使用）
- 2AZ / 3層サブネット（`front` / `application` / `datastore`）
- NAT Gateway 1台
- 共通タグ `env` / `service` / `version`（`version=1.00`）

## 004-awscdk_docker_image_deployment で追加された内容
- `Todo` ECR リポジトリを CDK で作成
- `backend/` の Dockerfile を `DockerImageAsset` でビルドし、`cdk-ecr-deployment` で ECR に配布
- 配布タグは `DockerImageAsset.imageTag`（イメージ内容に連動する可変タグ）を利用
- `RemovalPolicy.DESTROY`（サンプル要件として `prod` 含む全対象環境）
- ECR のイメージスキャン設定、ライフサイクルポリシー、タグ不変設定は未採用
- ECS サービス更新はこの機能の対象外（ECR 配布まで）

## 005-ecs-aurora-jpa で追加された内容
- ALB / ECS(Fargate) / Aurora Serverless v2(PostgreSQL) / Secrets Manager をCDKで作成
- ECR の可変タグイメージ（`DockerImageAsset.imageTag`）をECSタスク定義で参照
- DB接続情報をSecrets Manager経由でECSコンテナに注入
- ALB/ECS/Aurora 用 Security Group を追加し、`ALB -> ECS -> Aurora` の通信経路を明示
- ALB ヘルスチェック（`path=/`）とターゲットグループ連携を追加

## 006-api-and-springboot-controller-service で追加された内容
- CloudFront Distribution を追加し、公開経路を `CloudFront -> (/api/*) ALB -> ECS` に統一
- ALB の Security Group 受信元を CloudFront managed prefix list 起点に制限（ALB 直アクセス抑止）
- Cognito User Pool / App Client / Hosted UI Domain を追加
  - 自己登録可、MFA不要、簡易パスワードポリシー
  - App Client は Public Client（secret なし）+ Authorization Code Flow
  - callback/logout URL は CloudFront ドメインから動的生成
- ALB ヘルスチェックパスを `/actuator/health` に統一

## 008-frontend-basic で追加された内容
- S3（private）を frontend 静的配信バケットとして追加
- CloudFront default behavior を S3 origin（OAC）へ変更
- `/api/*` behavior は ALB origin + no-cache + Authorization 転送を維持
- SPA fallback（403/404 -> `/index.html`）を追加
- `s3deploy.BucketDeployment` で `frontend/dist` と `runtime-config.json` を配備
- Cognito App Client に Refresh Token Rotation を追加

## 017-test-data-cleanup で追加された内容
- 負荷試験後の Todo 掃除用に、手動起動専用 Lambda（Python 3.13）を追加
- Aurora PostgreSQL の Data API を有効化し、Lambda から VPC 非依存で削除処理を実行
- Lambda 実行入力 `userPrefix` は `loadtest_` または `*` のみ許可
- 1 回の削除件数は `BATCH_SIZE`（既定値 `500`）で分割実行
- Stack 出力 `TodoTestDataCleanupLambdaFunctionName` から対象関数名を確認可能

## 実行時の注意
- `cdk deploy` / `cdk synth` / `cdk diff` 実行時に Docker デーモンが必要です。
- AWS 認証情報に ECR への push 権限が必要です。
- `dev/stg/prod` いずれの実行でも、対象アカウント側の CDK lookup role を Assume できる認証が必要です。
- frontend を更新した場合は、`infra` 実行前に `frontend/` で `npm run build` を実行して `dist/` を生成してください。
- `userPrefix=*` は全ユーザーの Todo を削除するため、実行前に対象環境を必ず確認してください。

## 関連ドキュメント
- [ネットワーク詳細](../docs/infra/network-baseline.md)
- [ECR配布詳細](../docs/infra/ecr-image-deployment.md)
- [ECS/Aurora実行基盤](../docs/infra/ecs-aurora-runtime-baseline.md)
- [Cognito負荷試験ユーザー運用](../docs/load-test/load-test-operations.md)
- [ADR](../docs/adr/002-network-baseline-and-env-switching.md)
