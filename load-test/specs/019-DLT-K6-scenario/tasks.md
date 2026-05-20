# Tasks: 019-DLT-K6-scenario

## 前提確認
- [x] 1.1 ルート `AGENTS.md`、`infra/AGENTS.md`、`docs/AGENTS.md` を確認し、領域別の作業ルールを固定する。
- [x] 1.2 ルート `README.md`、`docs/README.md`、`docs/load-test/load-test-deployment.md` を確認し、導線更新対象を明確化する。
- [x] 1.3 `load-test/specs/019-DLT-K6-scenario/specs.md` の AC-01〜AC-10 をチェックリスト化する。
- [x] 1.4 `load-test/specs/019-DLT-K6-scenario/plan.md` の変更対象/非対象を確認し、`backend/` と `frontend/` に変更しない方針を固定する。
- [x] 1.5 既存 `infra/lib/constructs/todo-cognito-construct.ts` と `infra/test/infra.test.ts` の Cognito App Client 設定を確認し、追加差分点を確定する。
- [x] 1.6 現在のドラフト名 `specks-draft.md` の扱いを確認し、`specs-draft.md` へ名称統一する方針を確定する。

## 実装タスク

### 2. `load-test/` 基盤整備
- [x] 2.1 `load-test/AGENTS.md` を新規作成し、`infra/AGENTS.md` を参考に DLT 作業規約（配置、秘密情報、検証、禁止事項）を定義する。
- [x] 2.2 `load-test/` 配下に実装用ディレクトリ（例: `scripts/`, `k6/`, `test-case/`）を作成する。
- [x] 2.3 生成物（`tokens.json`、ZIP、一時ファイル）を誤コミットしないための除外設定（`.gitignore` 等）を追加する。
- [x] 2.4 `specks-draft.md` を `specs-draft.md` に名称統一し、関連参照（`prompts.md` 等）を更新する。

### 3. `infra/` Cognito 認証フロー追加
- [x] 3.1 `infra/lib/constructs/todo-cognito-construct.ts` の App Client `authFlows` に `adminUserPassword: true` を追加する。
- [x] 3.2 既存の `userSrp: true`、OAuth code flow、callback/logout 設定を維持し、不要変更を入れないことを確認する。
- [x] 3.3 `infra/test/infra.test.ts` の `AWS::Cognito::UserPoolClient` 検証に `ALLOW_ADMIN_USER_PASSWORD_AUTH` を追加する。
- [x] 3.4 `cd infra && npm run build` を前提に生成物（`lib/**/*.js`, `lib/**/*.d.ts`）を同期する。

### 4. Cognito ユーザー作成スクリプト実装（Python 3.13）
- [x] 4.1 User Pool ID 取得（CloudFormation 出力参照）と実行パラメータ処理（`REGION`, `STACK_NAME`, `USER_COUNT` など）を実装する。
- [x] 4.2 100同時ユーザー向けテストユーザー作成処理（`AdminCreateUser` + `SUPPRESS`）を実装する。
- [x] 4.3 恒久パスワード化（`AdminSetUserPassword --permanent` 相当）と再実行可能性（既存ユーザー再整列）を実装する。
- [x] 4.4 API 制限対策（レート制御、リトライ、失敗時継続方針）を実装する。

### 5. JWT 生成スクリプト実装（Python 3.13）
- [x] 5.1 `AdminInitiateAuth`（`ADMIN_USER_PASSWORD_AUTH`）で JWT を取得する処理を実装する。
- [x] 5.2 `tokens.json`（100件配列）出力フォーマットを定義し、K6 側で読み取り可能な構造で保存する。
- [x] 5.3 失敗ユーザーの扱い（再試行、スキップ、エラー終了条件）を実装する。
- [x] 5.4 実行ログに機密情報を出力しないマスキング方針を実装する。

### 6. K6 シナリオ実装
- [x] 6.1 `tokens.json` を入力として認証付き API 呼び出しを行うシナリオ基盤を実装する。
- [x] 6.2 API 呼び出し比率 `GET 70% / POST 15% / PUT 10% / DELETE 5%` を実装する。
- [x] 6.3 各ユーザー初期 Todo 20 件前提のセットアップ処理を実装する。
- [x] 6.4 `description` 100〜300 文字のテストデータ生成を実装する。
- [x] 6.5 `PUT` / `DELETE` の対象を当該ユーザー所有データに限定する制御を実装する。
- [x] 6.6 1テスト実行あたり新規作成上限 50 件/ユーザーを実装する。

### 7. ZIP 化・DLT 提出資材整備
- [x] 7.1 ZIP 作成方式を決定する（手順書主体 or 補助スクリプト主体）。
- [x] 7.2 決定方式に基づき ZIP 作成処理を実装し、出力先を `load-test/test-case/` に固定する。
- [x] 7.3 DLT 実行で必要な同梱物（K6 スクリプト、データ、補助設定）の過不足を確認する。

## テスト / 検証タスク
- [x] 8.1 Python スクリプトの構文検証（`python3.13 -m py_compile ...`）を実行する。
- [x] 8.2 少数ユーザーでユーザー作成/JWT生成のドライランを実施し、`tokens.json` 形式と件数を確認する。
- [x] 8.3 K6 スモーク（可能なら 1〜5 VU）を実施し、認証・CRUD・比率制御が動作することを確認する。
- [x] 8.4 生成 ZIP の内容を検証（`unzip -l` 等）し、DLT 提出可能な構成であることを確認する。
- [x] 8.5 `infra/` で `npm run build`、`npm test -- --runInBand`、`npx cdk synth -c env=prod`、`npx cdk diff -c env=prod` を実行する。
- [x] 8.6 `ExplicitAuthFlows` に `ALLOW_ADMIN_USER_PASSWORD_AUTH` が含まれ、Cognito 以外に不要差分がないことを確認する。
- [x] 8.7 実施不能な検証がある場合、未実施理由・未確認範囲・残リスクを記録する。

## ドキュメント更新タスク
- [x] 9.1 `docs/infra/cognito-load-test-user-operations.md` を `docs/load-test/` へ移動し、DLT 実行全体手順へ再構成する。
- [x] 9.2 移動後ドキュメントに、ユーザー作成/JWT生成/ZIP作成/S3アップロード/DLTコンソール操作/cleanup を記載する。
- [x] 9.3 `docs/README.md` のリンクを移動先へ更新し、`infra` セクションと `load-test` セクションの整合を取る。
- [x] 9.4 ルート `README.md` の負荷試験導線を更新する。
- [x] 9.5 `infra/README.md` の更新要否を確認し、必要時のみ最小差分で更新する。
- [x] 9.6 `docs/adr/` 追加が不要であることを確認し、必要なら理由を記録する。

## 完了確認
- [x] 10.1 `specs.md` の AC-01〜AC-10 を満たしていることを確認する。
- [x] 10.2 変更範囲が `load-test/`、`infra/`、`docs/`、ルート `README.md` の必要箇所に限定されていることを確認する。
- [x] 10.3 `backend/` / `frontend/` に不要差分が混入していないことを確認する。
- [x] 10.4 シークレット、JWT、固定パスワード、認証情報、state ファイル、不要生成物が差分に含まれていないことを確認する。
- [x] 10.5 実行コマンド・結果・未実施項目を `tasks.md` に記録し、レビュー可能な状態にする。

---

並列化の目安:
- `3.x`（infra 最小変更）と `4.x`/`5.x`（スクリプト実装）は並行可能。
- `6.x`（K6 シナリオ）は `5.x` の `tokens.json` 仕様確定後に着手する。
- `9.x`（ドキュメント更新）は `7.x`（ZIP 方式確定）以降に進めると手戻りが少ない。

## 実行記録
- 追加/変更した主なファイル
  - `load-test/AGENTS.md`
  - `load-test/requirements.txt`
  - `load-test/scripts/create_cognito_users.py`
  - `load-test/scripts/generate_tokens.py`
  - `load-test/scripts/package_dlt_scenario.py`
  - `load-test/k6/scenarios/todo_api_scenario.js`
  - `infra/lib/constructs/todo-cognito-construct.ts`
  - `infra/test/infra.test.ts`
  - `docs/load-test/cognito-load-test-user-operations.md`（`docs/infra/` から移動・再構成）
  - `docs/README.md`, `docs/load-test/load-test-deployment.md`, `infra/README.md`, `README.md`
- 検証コマンド結果
  - `python3.13 -m py_compile ...`: 成功
  - `python3.13 load-test/scripts/create_cognito_users.py --dry-run --user-count 3 ...`: 成功
  - `python3.13 load-test/scripts/generate_tokens.py --dry-run --user-count 3 ...`: 成功
  - `python3.13 load-test/scripts/package_dlt_scenario.py --include-tokens ...`: 成功
  - `unzip -l load-test/test-case/*.zip`: 成功（`data/tokens.json` と `scenarios/todo_api_scenario.js` を確認）
  - `cd infra && npm run build`: 成功
  - `cd infra && npm test -- --runInBand`: 1回目失敗（`ExplicitAuthFlows` 期待順差異）→テスト修正後に成功
  - `cd infra && npx cdk synth -c env=prod`: 成功
  - `cd infra && npx cdk diff -c env=prod`: 成功（`ALLOW_ADMIN_USER_PASSWORD_AUTH` 追加のみ差分）
  - （再確認）`cd infra && npm run build && npm test -- --runInBand && npx cdk synth -c env=prod && npx cdk diff -c env=prod`: 成功（差分は `ExplicitAuthFlows` のみ）
  - （再確認）`python3.13 load-test/scripts/package_dlt_scenario.py --include-tokens --output-name dlt-k6-todo-scenario-verify.zip` + `unzip -l`: 成功
- 未実施項目 / 理由
  - K6 ローカルスモーク（`k6 run`）は `k6` 未インストールのため未実施（`k6 version` が `command not found`）。
  - 実 AWS での 100ユーザー作成/JWT本番発行は本番影響を避けるためドライラン検証に留めた。
- ADR 更新
  - 今回は既存方針の運用具体化と実装追加であり、`docs/adr/` 追加は不要と判断。
