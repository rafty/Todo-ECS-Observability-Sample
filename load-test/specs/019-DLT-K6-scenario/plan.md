# Plan: 019-DLT-K6-scenario

## 実装方針
- 本 feature は **複数領域（`load-test/` + `infra/` + `docs/` + ルート `README.md`）** で実施する。
- 負荷試験の主処理は `load-test/` に集約し、責務を「ユーザー作成」「JWT 生成」「K6 実行」「ZIP 化」に分離する。
- Cognito 認証は `AdminInitiateAuth (ADMIN_USER_PASSWORD_AUTH)` 前提とし、既存 API 契約（`docs/backend/api.md`）に準拠した K6 シナリオを構成する。
- テスト対象環境は `prod` 固定とし、実行前提・操作手順・後片付け（cleanup）を運用手順として一気通貫で文書化する。
- DLT 向けアーカイブは `load-test/test-case/` に配置し、再実行可能な手順（または補助スクリプト）を提供する。
- `infra` では Cognito App Client の認証フローに `ALLOW_ADMIN_USER_PASSWORD_AUTH` を追加し、既存フローを維持した最小差分で実現する。

## 変更対象
- `load-test/`
  - `AGENTS.md`（新規）: `infra/AGENTS.md` を参考に、DLT 作業向けのルールを定義する。
  - `scripts/`（新規想定）:
    - Cognito テストユーザー作成スクリプト（Python 3.13）
    - JWT 生成スクリプト（Python 3.13, `AdminInitiateAuth`）
    - ZIP 生成補助スクリプト（採用時）
  - `scenario/` または `k6/`（新規想定）:
    - Todo API 用 K6 シナリオ
    - 入力データ（`tokens.json` 想定）
  - `test-case/`（新規想定）:
    - DLT 提出用 ZIP の出力先
- `infra/`
  - `infra/lib/constructs/todo-cognito-construct.ts`:
    - App Client `authFlows` に `adminUserPassword: true` を追加し、`ADMIN_USER_PASSWORD_AUTH` を許可する。
    - 既存 `userSrp: true` と OAuth code flow は維持する。
  - `infra/test/infra.test.ts`:
    - `AWS::Cognito::UserPoolClient` の `ExplicitAuthFlows` に `ALLOW_ADMIN_USER_PASSWORD_AUTH` が含まれることを検証する。
- `load-test/specs/019-DLT-K6-scenario/`
  - `plan.md`（本ファイル）
  - ドラフト要件ファイル名を `specs-draft.md` に統一
- `docs/`
  - `docs/infra/cognito-load-test-user-operations.md` を `docs/load-test/` 配下へ移動し、DLT 実行全体手順へ再編
  - `docs/README.md` のリンク導線更新
  - `docs/load-test/load-test-deployment.md` の関連導線更新（必要時）
- ルート `README.md`
  - 負荷試験ドキュメントへの導線更新

## 変更しないもの
- `backend/` 実装（API 機能、バリデーション、DB 操作ロジック）。
- `frontend/` 実装（認証 UI、画面遷移、runtime-config 処理）。
- AWS DLT ソリューション自体のデプロイ構成。
- Cognito App Client 認証フロー追加以外の `infra/lib/` 構成変更（ネットワーク、ECS、Aurora、CloudFront など）。
- 公開 API 契約（`/api/todos`）と DB スキーマ。

## 技術方針
- 既存パターンの再利用方針
  - Cognito 情報は既存の CloudFormation 出力（UserPoolId / ClientId / Hosted UI base URL など）を利用する。
  - 既存の cleanup 運用（Todo cleanup Lambda）を後処理手順として再利用する。
  - Cognito App Client は既存 Construct を拡張し、認証フロー追加のみを行う。
- スクリプト分割方針
  - ユーザー作成と JWT 生成は別スクリプトに分離し、失敗時の再実行単位を明確化する。
  - ユーザー作成は `AdminCreateUser` + `AdminSetUserPassword --permanent` の再実行可能フローを基本とする。
  - JWT 生成は `AdminInitiateAuth`（`AuthFlow=ADMIN_USER_PASSWORD_AUTH`）を用いて `tokens.json`（100件配列）を生成する。
- K6 シナリオ方針
  - 読み取り/書き込み比率は `GET 70% / POST 15% / PUT 10% / DELETE 5%` を固定実装する。
  - 各ユーザー初期 Todo 20 件前提、`description` 100〜300 文字生成、1実行あたり新規 50 件上限を実装で担保する。
  - `PUT` / `DELETE` はユーザー単位で所有データに限定し、他ユーザーデータへ触れない制御を入れる。
- パッケージング方針
  - ZIP 化は「作業者負荷が低い方式」を採用する。
  - 初期実装は補助スクリプト化を優先し、手順書には手動代替手順も残す。
- 新規依存追加方針
  - Python は `boto3` 中心で構成し、追加依存は最小限にする。
- インフラ変更方針
  - 変更対象は Cognito App Client の `authFlows` 追加に限定する。
  - `cdk diff` で Cognito 関連以外に差分が出ないことを確認する。

## データや契約への影響
- DB スキーマ: 変更なし。
- API 契約: 変更なし（`docs/backend/api.md` を参照して負荷試験を構成）。
- イベント契約: 変更なし。
- 環境変数:
  - スクリプト実行用の入力値（`REGION`、`STACK_NAME`、`USER_COUNT` など）を運用パラメータとして定義する。
- Secret / 設定値:
  - 固定パスワードや JWT は運用時データとして扱い、リポジトリへコミットしない。
  - `tokens.json` は生成物として秘匿管理し、永続保管しない運用を基本とする。
- デプロイ/運用影響:
  - `infra` の `cdk deploy` が必要（Cognito App Client 設定変更）。
  - DLT 実行手順追加により運用オペレーションが標準化される。

## リスク
- セキュリティリスク
  - `tokens.json` や固定パスワードの漏えいリスク。
  - 対策: `.gitignore`/保存場所ルール/実行後削除手順を文書化する。
- 運用リスク
  - `prod` 固定運用で誤操作した場合、実トラフィックへの影響が大きい。
  - 対策: 実行前チェックリスト（対象スタック、リージョン、ユーザー接頭辞）を手順に必須化する。
- 性能・コストリスク
  - 100 同時ユーザー負荷で ECS/Aurora への負荷集中と DLT 実行コスト増加が発生する。
  - 対策: 小規模スモーク（例: 5〜10 VU）を先行し、段階的に 100 同時へ上げる。
- 実装リスク
  - `AdminInitiateAuth` 実行には IAM 権限が必須で、権限不足時に JWT 生成が失敗する。
  - 対策: `cognito-idp:AdminInitiateAuth` を含む最小権限ポリシーを手順化し、事前検証を実施する。
- 互換性リスク
  - App Client の認証フロー追加時に既存ログイン動線へ意図しない影響が出る可能性。
  - 対策: 既存 `ALLOW_USER_SRP_AUTH` / OAuth code flow を維持し、`infra.test.ts` と `cdk diff` で回帰確認する。
- ドキュメントリスク
  - ファイル移動後に `docs/README.md` や `README.md` のリンク切れが残る可能性。
  - 対策: 参照リンクを一覧で点検し、移動元リンクを全件更新する。

## 検証方針
- スクリプト検証
  - Python 構文検証: `python3.13 -m py_compile <scripts...>`
  - 主要フローのドライラン（少数ユーザー）で入出力形式を確認する。
- `infra` 検証
  - `cd infra && npm run build`
  - `cd infra && npm test -- --runInBand`
  - `cd infra && npx cdk synth -c env=prod`
  - `cd infra && npx cdk diff -c env=prod`
  - `AWS::Cognito::UserPoolClient` の `ExplicitAuthFlows` に `ALLOW_ADMIN_USER_PASSWORD_AUTH` が含まれることを確認する。
- K6 検証
  - ローカルで可能なら低負荷スモーク（例: 1〜5 VU）を実施し、認証・CRUD・比率制御を確認する。
  - 実行環境に `k6` がない場合は、未実施理由を明記する。
- パッケージ検証
  - 生成 ZIP の中身（シナリオ/データ/補助ファイル）を `unzip -l` 等で検証する。
  - DLT へアップロード可能な構成になっていることを手順と照合する。
- ドキュメント検証
  - 移動後リンクの有効性確認（`docs/README.md`、ルート `README.md`、関連 docs）。
  - 手順書が「準備→実行→cleanup」まで欠けなく記載されていることをレビューで確認する。

## ドキュメント更新方針
- 更新対象
  - `docs/load-test/`：DLT 実行全体手順（ユーザー作成、JWT、ZIP、S3、DLT 操作、cleanup）を記載。
  - `docs/README.md`：移動後ドキュメントへの導線更新。
  - ルート `README.md`：負荷試験関連ドキュメントへの導線更新。
  - `load-test/AGENTS.md`：DLT 向け作業ルールを新規定義。
  - `infra/README.md`：Cognito 負荷試験運用導線の更新要否を確認し、必要時のみ更新する。
- ADR 要否
  - 今回は運用手順と資材整理が中心のため、原則 `docs/adr/` 更新は不要。
  - 認証方式や本番運用ポリシーを恒久変更する場合は別途 ADR を検討する。

## 実施順序
1. 現状確認と土台作成
   - `load-test/` のディレクトリ構成を決め、`AGENTS.md` を新規作成する。
2. `infra` 最小変更
   - Cognito App Client に `adminUserPassword: true` を追加し、テストを更新する。
   - `synth/diff` で差分が想定範囲に収まることを確認する。
3. データ準備系スクリプト実装
   - ユーザー作成スクリプトを実装し、再実行可能性を担保する。
   - JWT 生成スクリプトを実装し、`tokens.json` 出力を確定する。
4. K6 シナリオ実装
   - API 比率、データ粒度、上限、所有者境界を満たすシナリオを実装する。
5. ZIP 化導線実装
   - 手順書主体または補助スクリプト主体のいずれかで、最小工数の ZIP 作成フローを整備する。
6. ドキュメント再編
   - `docs/infra/cognito-load-test-user-operations.md` を `docs/load-test/` へ移動・再構成する。
   - `docs/README.md` とルート `README.md` の導線を更新する。
7. 検証と仕上げ
   - スクリプト/K6/ZIP/リンクの検証を実施し、未実施項目は理由を明記する。
8. 仕様資産整合
   - `specs-draft.md` 命名統一と、feature 配下文書の参照整合を最終確認する。

## 決定事項
- ZIP 生成方式は「補助スクリプト主体」を採用する。
  - 実装: `load-test/scripts/package_dlt_scenario.py`
  - 出力先: `load-test/test-case/`
  - 手順書には補助スクリプト実行を標準手順として記載し、必要に応じて手動確認手順（`unzip -l`）を併記する。
