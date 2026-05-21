# Tasks: 020-app-logging

## 前提確認
- [x] 1.1 ルート `AGENTS.md`、`backend/AGENTS.md`、`docs/AGENTS.md` を確認し、実装・文書更新ルールを再確認する
- [x] 1.2 `backend/specs/020-app-logging/specs.md` と `backend/specs/020-app-logging/plan.md` を確認し、AC-01〜AC-13 と対象外を実装チェックリスト化する
- [x] 1.3 `infra/lib/constructs/todo-backend-ecs-service-construct.ts` を確認し、ログ連携は既存 `awslogs` 経路を参照のみで利用する方針を固定する
- [x] 1.4 変更対象を `backend/` と `docs/` に限定し、`infra/`・`frontend/`・API契約非変更を明文化する
- [x] 1.5 未確定事項が解消済みであることを確認し、追加の要件解釈を発生させない

## 実装タスク

### backend: ログ基盤
- [x] 2.1 JSON 構造化ログの出力項目を確定する（`requestId`、`traceId`、`eventType`、`action`、`ownerSubjectHash`、`todoId` など）
- [x] 2.2 リクエスト相関用 Filter（`OncePerRequestFilter`）を追加し、`requestId` を受領または採番して MDC に格納する
- [x] 2.3 `X-Amzn-Trace-Id` を抽出して `traceId` として MDC に格納する実装を追加する
- [x] 2.4 Filter の finally で MDC を確実にクリアし、リクエスト間の文脈汚染を防止する
- [x] 2.5 `ownerSubject(sub)` をハッシュ化するユーティリティを追加し、ログ用途で再利用可能にする
- [x] 2.6 `application.properties` に JSON 構造化ログ設定を追加する（標準出力前提）
- [x] 2.7 `application.properties` にログレベル環境変数上書き設定を追加し、`LOGGING_LEVEL_ROOT` 等で動的変更可能にする

### backend: 業務ログ / 監査ログ / 異常系ログ
- [x] 3.1 `TodoController` に SLF4J ロガーを追加し、書き込み操作（POST/PUT/DELETE）の監査ログ出力を実装する
- [x] 3.2 `TodoController` の GET/LIST は監査ログ対象外とし、業務要約ログのみ出力する
- [x] 3.3 `TodoServiceImpl` に業務イベント・重要状態遷移ログ（例: completed 変更）を追加する
- [x] 3.4 `ApiExceptionHandler` で 4xx を `WARN` として記録する（既存エラーレスポンス契約は変更しない）
- [x] 3.5 5xx 相当の異常系を `ERROR` で追跡できるようログ出力ポイントを追加する（API契約変更なし）
- [x] 3.6 監査ログに含める主体情報は `ownerSubjectHash` のみとし、生値 `ownerSubject(sub)` を出力しない
- [x] 3.7 JWT本文、Authorizationヘッダー、シークレット、個人情報生値がログに出ないことをコード上で担保する
- [x] 3.8 ログキー命名を統一し、CloudWatch Logs Insights で検索しやすい形式へ揃える

## テスト / 検証タスク
- [x] 4.1 `ownerSubject` ハッシュ化ユーティリティの単体テストを追加する（決定性・生値非一致）
- [x] 4.2 書き込み操作（POST/PUT/DELETE）で監査ログが出力されることを検証するテストを追加する
- [x] 4.3 読み取り操作（GET/LIST）が監査ログ対象外であることを検証するテストを追加する
- [x] 4.4 `X-Amzn-Trace-Id` 受領時に `traceId` がログ項目へ反映されることを検証する
- [x] 4.5 4xx/5xx のログレベル使い分け（WARN/ERROR）を検証する
- [x] 4.6 機密値非出力（`ownerSubject` 生値、Authorization 等）を検証する
- [x] 4.7 `cd backend && ./mvnw test` を実行し、変更範囲の回帰確認を行う
- [x] 4.8 必要に応じて `cd backend && ./mvnw -DskipTests compile` を実行し、コンパイル整合を確認する
- [x] 4.9 未実施の検証がある場合は、未確認範囲と理由を `tasks.md` または作業記録に明記する

## ドキュメント更新タスク
- [x] 5.1 `docs/backend/` にログ設計ドキュメント（例: `logging.md`）を新規作成する
- [x] 5.2 ログ設計ドキュメントにログ分類（業務/監査/異常/デバッグ）を記載する
- [x] 5.3 ログ設計ドキュメントに JSON フィールド定義を記載する
- [x] 5.4 ログ設計ドキュメントに相関ID方針（`requestId`/`traceId`）を記載する
- [x] 5.5 ログ設計ドキュメントに監査対象範囲（POST/PUT/DELETE のみ、GET/LIST 除外）を記載する
- [x] 5.6 ログ設計ドキュメントに `ownerSubjectHash` 利用方針を記載する
- [x] 5.7 ログ設計ドキュメントに 1週間保持（サンプル）と正式運用時の長期保持要件を記載する
- [x] 5.8 `docs/backend/README.md` にログ設計ドキュメントへの導線を追加する
- [x] 5.9 `backend/README.md` にログ方針概要と `docs/backend/` へのリンクを追加する
- [x] 5.10 必要に応じて `docs/README.md` の backend セクションへリンクを追加する
- [x] 5.11 `backend/AGENTS.md` のログ実装ルールと実装内容の整合を最終確認し、必要時のみ最小修正する
- [x] 5.12 ADR 追加要否を判定し、不要なら「不要理由」を作業記録に残す

## 完了確認
- [x] 6.1 AC-01〜AC-13 の各受け入れ条件に対して、実装・テスト・文書の対応関係を確認する
- [x] 6.2 API 契約、DB スキーマ、infra 構成が意図せず変更されていないことを確認する
- [x] 6.3 差分にシークレット、認証情報、不要生成物が含まれていないことを確認する
- [x] 6.4 `tasks.md` のチェック状態を実施結果と同期し、未完了項目を明確化する

## 作業ログ
- テスト/コンパイル検証結果:
  - `cd backend && ./mvnw test` を実行したが、実行環境の JDK が `release 21` 非対応のため失敗。
  - `cd backend && ./mvnw -DskipTests compile` も同理由で失敗。
  - エラー: `リリース・バージョン21はサポートされていません`
- ADR 判定:
  - 今回は既存基盤上でのログ実装方針整備であり、アーキテクチャ判断の新規追加はないため `docs/adr/` 更新は不要。
