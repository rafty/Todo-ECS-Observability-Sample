# Plan: 017-test-data-cleanup

## 実装方針
- 本 feature は **複数領域（`infra/` + `docs/`）** で実施する。
- Todo の一括削除は管理者 UI/API を追加せず、**手動起動専用 Lambda バッチ**として実装する。
- Lambda から Aurora へのアクセスは、Python ドライバ同梱を避けて保守性を上げるため、**RDS Data API** を採用する。
- 実行入力は `userPrefix` のみを必須とし、許可値を `loadtest_` と `*` に限定する。
- `prod` を含む `dev/stg/prod` の同一方式で運用し、環境別の実行抑止ロジックは入れない。
- 既存 Stack/Construct の分割方針を維持し、cleanup 機能は専用 Construct に切り出して最小差分で追加する。

## 変更対象
- `infra/lib/constructs/todo-aurora-construct.ts`
  - Data API 利用のため、Aurora Cluster の `enableDataApi` を有効化する。
  - 既存の DB 名、容量、Secret 名、削除ポリシーは維持する。
- `infra/lib/constructs/todo-test-data-cleanup-lambda-construct.ts`（新規）
  - Python 3.13 Lambda、実行ロール、ログ設定、RDS Data API 権限付与をまとめる専用 Construct を追加する。
  - `cluster.grantDataApiAccess(...)` と Secret 読み取り権限を最小単位で付与する。
- `infra/lib/infra-stack.ts`
  - Cleanup Lambda Construct を組み込み、Aurora Construct と連携する。
  - 運用導線として必要であれば Lambda 関数名を `CfnOutput` に追加する。
- `infra/lambda/todo-test-data-cleanup/handler.py`（新規）
  - 手動実行イベント（`userPrefix`）を受け取り、対象件数取得とバッチ削除を実装する。
  - CloudWatch Logs に実行サマリ（prefix、対象件数、削除件数、成否）を出力する。
- `infra/test/infra.test.ts`
  - Cleanup Lambda が作成されること、`Runtime: python3.13`、Data API 権限関連が反映されることを検証する。
- `infra/lib/**/*.js` / `infra/lib/**/*.d.ts`
  - `npm run build` により TypeScript 生成物を同期する。
- `infra/README.md`、`docs/infra/*.md`（必要に応じて）
  - 手動実行手順、入力例、注意点（`*` は全削除）を追記する。

## 変更しないもの
- `backend/`、`frontend/` のアプリケーション機能。
- 既存の公開 API 契約（`/api/*`）や認証方式（Cognito/ALB 経由）。
- 負荷試験ユーザー作成ロジック（Cognito 管理運用）。
- 自動実行（EventBridge Scheduler、Step Functions、CLI バッチの常設化）。
- `prod` 実行抑止や追加承認フロー（本 feature の要件外）。

## 技術方針
- 既存パターンの再利用方針
  - 既存の Construct 分割ルールに合わせ、cleanup 機能は `infra/lib/constructs/` に独立配置する。
  - Stack は「構成の組み立て」に留め、削除ロジック本体は Lambda コードへ閉じる。
- Lambda 実装方針
  - ランタイムは `aws_lambda.Runtime.PYTHON_3_13` を利用する。
  - 追加トリガーは作成せず、`on-demand invoke` のみ。
  - イベント入力仕様は以下で固定する:
    - `{"userPrefix":"loadtest_"}`
    - `{"userPrefix":"*"}`
  - それ以外は `ValueError` として失敗終了する。
- 削除アルゴリズム
  - 事前に `COUNT(*)` で対象件数を取得しログ出力する。
  - 大量削除時のロック/タイムアウトリスクを下げるため、`LIMIT` 付き CTE で分割削除をループ実行する。
  - 1 回あたりの削除件数（例: 500）は定数化し、必要なら環境変数で上書き可能にする。
  - `userPrefix='loadtest_'` は `owner_subject LIKE 'loadtest_%'` 条件、`userPrefix='*'` は無条件削除で処理する。
- 権限・セキュリティ方針
  - 実行権限は要件どおり「Lambda を手動起動できる IAM ユーザー/ロール」を許可対象とする。
  - Lambda 実行ロールは Data API 実行対象を当該 Aurora Cluster ARN に限定し、不要な広域権限は付与しない。
  - DB 資格情報は既存 Secrets Manager を参照し、コード内に埋め込まない。
- 新規依存追加の要否
  - Python の追加外部ライブラリは原則追加しない（`boto3` 利用）。
  - TypeScript 側は既存 `aws-cdk-lib` で実装可能なため、追加 npm 依存は原則不要。

## データや契約への影響
- DB スキーマ
  - 変更なし（`todos` テーブル定義・インデックスは現状維持）。
- API 契約
  - 公開 API の追加/変更なし。
- イベント契約
  - Lambda 手動実行時の入力 JSON 契約を新規追加する（`userPrefix` 必須）。
- 環境変数
  - Lambda 用に `DB_CLUSTER_ARN`、`DB_SECRET_ARN`、`DB_NAME`、`BATCH_SIZE`（任意）を追加予定。
- Secret / 設定値
  - 既存 DB Secret（`/todo/<env>/backend/database`）を再利用する。
- デプロイ/運用影響
  - Aurora Cluster の Data API 有効化（`EnableHttpEndpoint`）差分が発生する。
  - 運用手順に「Lambda コンソールからの手動実行」が追加される。

## リスク
- 誤操作リスク（`userPrefix='*'`）
  - 全件削除により業務データを消去する可能性がある。
  - 対策: 実行イベント値を厳格検証し、実行ログへ明示的に `userPrefix` と削除件数を残す。
- 性能/可用性リスク
  - 一括削除クエリが長時間化すると Aurora 負荷が上がる可能性がある。
  - 対策: 分割削除、バッチサイズ制御、Lambda タイムアウトを適切に設定する。
- セキュリティリスク
  - Data API 有効化に伴い、誤った IAM 権限付与で DB 操作面が拡大する可能性がある。
  - 対策: cleanup Lambda ロール以外に Data API 権限を付与しないことを `cdk diff` で確認する。
- 運用リスク
  - 手動運用のみのため、手順の理解不足で誤入力が発生する可能性がある。
  - 対策: docs に実行例（`loadtest_` / `*`）と注意事項を明記する。

## 検証方針
- ビルド/テスト
  - `cd infra && npm run build`
  - `cd infra && npm test -- --runInBand`
- CDK 合成/差分確認
  - `cd infra && npx cdk synth -c env=dev`
  - `cd infra && npx cdk synth -c env=stg`
  - `cd infra && npx cdk synth -c env=prod`
  - `cd infra && npx cdk diff -c env=dev`
  - `cd infra && npx cdk diff -c env=stg`
  - `cd infra && npx cdk diff -c env=prod`
- テンプレート観点
  - Cleanup Lambda が `python3.13` で作成されること。
  - EventBridge 等の自動トリガーが追加されていないこと。
  - Lambda ロールに Data API と Secret 読み取りの最小権限が付与されていること。
  - Aurora 側に Data API 有効化差分が出ていること（意図した差分であること）。
- 手動動作確認（対象環境で実施可能な場合）
  - `userPrefix=loadtest_` で対象プレフィックスのみ削除されること。
  - `userPrefix=*` で全件削除されること。
  - 不正値（例: `loadtest`、`all`）で失敗し、エラーがログに出ること。

## ドキュメント更新方針
- `infra/README.md`
  - Cleanup Lambda の目的、手動実行前提、実行入力の最小仕様を追記する。
- `docs/infra/ecs-aurora-runtime-baseline.md`
  - 構成図に Cleanup Lambda と Aurora(Data API) 経路を追記する。
- `docs/infra/cognito-load-test-user-operations.md`（または新規運用文書）
  - 負荷試験後の Todo クリーンアップ手順を追加する。
- `docs/README.md`
  - 追加/更新した infra 文書への導線を更新する。
- ADR 要否
  - 既存アーキテクチャの補助運用機能追加であり、原則 ADR 追加は不要。

## 実施順序
1. 設計確定
   - `specs.md` 要件（手動起動、許可値、prod 実行可）に合わせ、Data API 採用の最終確認を行う。
2. Lambda 実装
   - `handler.py` に入力検証、件数取得、分割削除、ログ出力を実装する。
3. CDK 組み込み
   - Cleanup Lambda Construct を追加し、Aurora/Secret 権限と Stack 組み込みを実施する。
4. テスト更新
   - `infra.test.ts` に Lambda と IAM/Runtime の検証観点を追加する。
5. ビルドと検証
   - `build`、`test`、`synth`、`diff` を実行し、無関係な高リスク差分がないことを確認する。
6. ドキュメント反映
   - 実行手順と構成図の更新要否を確認し、必要文書を最小差分で更新する。

## 未解決事項
- 1 回あたりの削除バッチサイズの初期値（例: 500 / 1000）をどこに固定するか。
- Lambda のタイムアウト/メモリ値の初期設定（想定データ量と実行時間のバランス）。
- 運用文書の配置先を既存文書追記にするか、新規文書（例: `docs/infra/todo-test-data-cleanup-operations.md`）に分離するか。
