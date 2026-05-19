# Tasks: 017-test-data-cleanup

## 前提確認
- [x] 1.1 ルート `AGENTS.md` と `infra/AGENTS.md` を再確認し、変更範囲を `infra/` + 必要な `docs/` に限定する。
- [x] 1.2 `infra/README.md` と `docs/README.md` を確認し、実行コマンドとドキュメント導線更新ルールを再確認する。
- [x] 1.3 `infra/specs/017-test-data-cleanup/specs.md` の受け入れ条件（AC-01〜AC-06）をチェックリスト化する。
- [x] 1.4 `infra/specs/017-test-data-cleanup/plan.md` の変更対象/非対象を確認し、管理者UI/API・自動実行を追加しない方針を固定する。
- [x] 1.5 実装前に既存コード（Aurora Construct / Stack / test）を確認し、差分の最小化方針を明確にする。
- [x] 1.6 未解決事項（バッチサイズ、Lambda タイムアウト/メモリ、運用文書の配置先）を実装前提として一旦確定する。

## 実装タスク

### 2. Lambda 削除ロジック実装
- [x] 2.1 `infra/lambda/todo-test-data-cleanup/handler.py` を新規作成し、エントリーポイントを実装する。
- [x] 2.2 実行入力 `userPrefix` のバリデーションを実装し、許可値を `loadtest_` と `*` のみに制限する。
- [x] 2.3 `userPrefix=loadtest_` は `owner_subject LIKE 'loadtest_%'`、`userPrefix=*` は全件対象となる条件分岐を実装する。
- [x] 2.4 事前件数取得（`COUNT(*)`）を実装し、削除開始前に対象件数をログ出力する。
- [x] 2.5 分割削除ループ（バッチ削除）を実装し、タイムアウト/長時間ロックを避ける構成にする。
- [x] 2.6 実行結果ログ（開始/終了、`userPrefix`、対象件数、削除件数、成否）を実装する。
- [x] 2.7 不正入力時・SQL実行失敗時のエラーハンドリングと失敗ログを実装する。

### 3. Infra Construct 追加
- [x] 3.1 `infra/lib/constructs/todo-test-data-cleanup-lambda-construct.ts` を新規作成し、Python 3.13 Lambda を定義する。
- [x] 3.2 Lambda の実行環境変数（`DB_CLUSTER_ARN`、`DB_SECRET_ARN`、`DB_NAME`、`BATCH_SIZE`）を定義する。
- [x] 3.3 CloudWatch Logs の保持期間・削除ポリシーを既存方針に沿って設定する。
- [x] 3.4 Lambda 実行ロールへ RDS Data API 実行権限を最小権限で付与する。
- [x] 3.5 Lambda 実行ロールへ DB Secret 読み取り権限を最小権限で付与する。
- [x] 3.6 EventBridge などの自動トリガーを追加しないことを確認する。

### 4. 既存 Construct / Stack 連携
- [x] 4.1 `infra/lib/constructs/todo-aurora-construct.ts` を更新し、Aurora の Data API（`enableDataApi`）を有効化する。
- [x] 4.2 `infra/lib/infra-stack.ts` に cleanup Lambda Construct を組み込み、Aurora Construct と連携する。
- [x] 4.3 必要であれば Lambda 関数名の `CfnOutput` を追加し、手動運用導線を明確化する。
- [x] 4.4 既存の VPC/ECS/ALB/Cognito/frontend 配備ロジックに不要差分が入っていないことを確認する。

### 5. テストコード更新
- [x] 5.1 `infra/test/infra.test.ts` に cleanup Lambda の作成検証を追加する。
- [x] 5.2 `Runtime: python3.13` の検証を追加する。
- [x] 5.3 Data API 権限付与・Secret 読み取り権限付与の検証観点を追加する。
- [x] 5.4 自動トリガー未追加（EventBridge リソース増加なし）を検証する。

### 6. 生成物同期
- [x] 6.1 `cd infra && npm run build` を実行し、TypeScript 生成物（`lib/**/*.js` / `lib/**/*.d.ts`）を同期する。
- [x] 6.2 生成物に本件と無関係な不要差分がないことを確認する。

## テスト / 検証タスク
- [x] 7.1 `cd infra && npm test -- --runInBand` を実行し、既存テスト回帰がないことを確認する。
- [x] 7.2 `cd infra && npx cdk synth -c env=dev` を実行する。
- [x] 7.3 `cd infra && npx cdk synth -c env=stg` を実行する。
- [x] 7.4 `cd infra && npx cdk synth -c env=prod` を実行する。
- [x] 7.5 `cd infra && npx cdk diff -c env=dev` を実行する。
- [x] 7.6 `cd infra && npx cdk diff -c env=stg` を実行する。
- [x] 7.7 `cd infra && npx cdk diff -c env=prod` を実行する。
- [x] 7.8 合成テンプレートまたは diff で cleanup Lambda が作成されることを確認する。
- [x] 7.9 合成テンプレートまたは diff で `Runtime: python3.13` を確認する。
- [x] 7.10 合成テンプレートまたは diff で Aurora の Data API 有効化差分を確認する。
- [x] 7.11 合成テンプレートまたは diff で自動トリガーが増えていないことを確認する。
- [x] 7.12 本件と無関係な高リスク差分（VPC/ECS/ALB/IAM 大幅変更）がないことを確認する。
- [x] 7.13 実施不能な検証がある場合、未実施理由・未確認範囲・残リスクを記録する。

## ドキュメント更新タスク
- [x] 8.1 `infra/README.md` に cleanup Lambda の目的・手動実行前提・入力例（`loadtest_` / `*`）を追記する。
- [x] 8.2 `docs/infra/ecs-aurora-runtime-baseline.md` に cleanup Lambda + Aurora(Data API) の経路を追記する。
- [x] 8.3 `docs/infra/cognito-load-test-user-operations.md` へテスト後Todoクリーンアップ手順を追記する、または新規運用文書を作成する。
- [x] 8.4 `docs/README.md` の infra セクション導線を必要に応じて更新する。
- [x] 8.5 ADR 追加が不要であることを確認し、必要なら理由を記録する。

## 完了確認
- [x] 9.1 `specs.md` の AC-01〜AC-06 を満たしていることを確認する。
- [x] 9.2 変更範囲が `infra/` + 必要な `docs/` に限定されていることを確認する。
- [x] 9.3 `prod` 実行可、`userPrefix` 制約、手動起動のみ、Python 3.13 の要件反映を最終確認する。
- [x] 9.4 シークレット、認証情報、state ファイル、不要生成物が差分に含まれていないことを確認する。
- [x] 9.5 実行コマンドと結果、未実施理由を `tasks.md` に記録し、レビュー可能な状態にする。

---

並列化の目安:
- `2.x`（Lambda ロジック）と `3.x`（Construct 追加）はインターフェース合意後に並行着手可能。
- `4.x`（Stack 連携）は `3.x` の公開プロパティ確定後に実施する。
- `8.x`（ドキュメント更新）は `4.x` の実装内容が固まり次第、`7.x` の検証と部分並行できる。

## 実行記録
- 実装前提の確定
  - `BATCH_SIZE` 既定値は `500` を採用
  - Lambda のタイムアウトは `15分`、メモリは `512MB` を採用
  - 運用手順は新規文書追加ではなく `docs/infra/cognito-load-test-user-operations.md` 追記を採用
- 追加/変更した主なファイル
  - `infra/lambda/todo-test-data-cleanup/handler.py`
  - `infra/lib/constructs/todo-test-data-cleanup-lambda-construct.ts`
  - `infra/lib/constructs/todo-aurora-construct.ts`
  - `infra/lib/infra-stack.ts`
  - `infra/test/infra.test.ts`
  - `infra/README.md`
  - `docs/infra/ecs-aurora-runtime-baseline.md`
  - `docs/infra/cognito-load-test-user-operations.md`
  - `docs/README.md`
- コマンド実行結果
  - `npm run build`: 成功
  - `npm test -- --runInBand`: 成功（1 test passed）
  - `npx cdk synth -c env=dev`: 失敗（`self-signed certificate in certificate chain` により lookup role Assume 不可）
  - `npx cdk synth -c env=stg`: 失敗（同上）
  - `npx cdk synth -c env=prod`: 成功
  - `npx cdk diff -c env=dev`: 失敗（`self-signed certificate in certificate chain`）
  - `npx cdk diff -c env=stg`: 失敗（同上）
  - `npx cdk diff -c env=prod`: 失敗（`self-signed certificate in certificate chain`）
- 合成テンプレート確認（`infra/cdk.out/InfraStack-prod.template.json`）
  - cleanup Lambda リソースが存在することを確認
  - cleanup Lambda の Runtime が `python3.13` であることを確認
  - Aurora の `EnableHttpEndpoint: true`（Data API 有効化）を確認
  - `AWS::Events::Rule` が存在しないことを確認（自動トリガー未追加）
  - `rds-data:ExecuteStatement` と `secretsmanager:GetSecretValue` が含まれることを確認
- 未確認範囲 / 残リスク
  - `cdk diff` は環境接続エラーのため `dev/stg/prod` すべて未完了
  - 実環境へのデプロイ後動作（Lambda 手動実行での実データ削除）は未実施
