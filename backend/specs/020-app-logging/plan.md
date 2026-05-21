# Plan: 020-app-logging

## 実装方針
- 本 feature は **複数領域**（`backend/` + `docs/`）で実施する。実装コードは `backend/` が中心で、`docs/backend/` と README 群を更新する。
- API 契約は変更せず、既存の Spring Boot ロギング基盤（SLF4J + Logback）を拡張して要件を満たす。
- AWS 観点では、ECS コンテナ標準出力 -> CloudWatch Logs という既存経路を維持し、CloudWatch Logs Insights で相関調査できる JSON ログを整備する。
- 監査ログはアプリケーション監査ログに限定し、Aurora 側監査ログ（`pgaudit` 等）は対象外とする。
- `ownerSubject(sub)` は生値を出さず、ハッシュ化した値のみを出力する。
- 本番ログレベルは環境変数で動的変更可能にし、通常は `INFO` を既定とする。

## 変更対象
- `backend/src/main/resources/application.properties`
  - JSON 構造化ログ設定（console 出力）を追加する。
  - ログレベルを環境変数で上書き可能にする設定を追加する。
- `backend/src/main/java/com/example/backend/` 配下
  - リクエスト相関情報（`requestId`, `traceId`, `path`, `httpMethod`）を MDC に設定/解除する Filter を追加する。
  - `ownerSubject` のハッシュ化ユーティリティ（例: SHA-256）を追加する。
  - `TodoController` / `TodoServiceImpl` / `ApiExceptionHandler` にログ出力（業務・監査・異常系）を追加する。
  - 書き込み操作（POST/PUT/DELETE）のみ監査ログ出力、GET/LIST は監査ログ対象外を実装で担保する。
- `backend/src/test/java/com/example/backend/` 配下
  - ハッシュ化ロジックの単体テストを追加する。
  - ログ出力の主要条件（監査対象、機密値非出力、相関キー存在）を検証するテストを追加する。
- `docs/backend/`
  - ログ設計ドキュメント（新規）を追加する。
  - `docs/backend/README.md` に導線を追加する。
- `backend/README.md` / `docs/README.md`
  - 必要に応じてログ設計ドキュメントへのリンクを追記する。
- `backend/AGENTS.md`
  - 既に追記済みルールとの差分を確認し、実装内容と齟齬があれば最小修正する。

## 変更しないもの
- `/api/todos` のエンドポイント、リクエスト/レスポンス構造、HTTP ステータスなど API 契約。
- DB スキーマ、Flyway マイグレーション。
- `infra/` の CDK 構成（CloudWatch Logs 連携は既存利用のみ）。
- OTel / X-Ray / OpenSearch / SIEM / CloudWatch Alarm の新規導入。
- Aurora 側監査ログ（DB 監査）設定。

## 技術方針
- 既存パターンの再利用方針
  - ロガーは各クラスに SLF4J Logger を定義し、既存の責務分離（Controller/Service/ExceptionHandler）を維持して出力する。
  - 例外変換は `ApiExceptionHandler` に集約済みのため、4xx の警告ログはここを中心に記録する。
  - 既存の ECS `awslogs` ドライバ構成を前提に、アプリ側は標準出力のみを利用する。
- 新規クラス/コンポーネント
  - `OncePerRequestFilter` ベースの相関ID/MDC 管理コンポーネントを追加する。
  - `ownerSubject` ハッシュ化専用コンポーネントを追加する（ビジネスロジックから分離）。
- JSON 構造化ログ
  - Spring Boot の structured logging 機能を優先利用し、console を JSON 形式で出力する。
  - 必須キー（`requestId`, `traceId`, `eventType`, `action`, `ownerSubjectHash`, `todoId`）は MDC またはログメッセージ設計で一貫して出力する。
- ログレベル運用
  - デフォルトは `INFO`。
  - `LOGGING_LEVEL_ROOT` と package logger 向け環境変数で動的変更可能にする。
- 新規依存追加
  - 原則追加しない（Spring Boot 標準機能 + JDK 標準 API で実装）。
- 既存インフラ/契約との関係
  - `infra` は **参照のみで変更しない**。
  - CloudWatch Logs の 1 週間保持は現行設定を前提にし、正式運用での長期保持要件はドキュメントで明示する。

## データや契約への影響
- DB スキーマ: 変更なし。
- API 契約: 変更なし。
- イベント契約: 外部イベントなし。
- 環境変数:
  - 追加/利用予定: `LOGGING_LEVEL_ROOT`（必須ではなく任意上書き）。
  - 必要に応じて package logger 用環境変数も利用する。
- Secret / 設定値:
  - 新規 Secret は追加しない。
- デプロイ/運用への影響:
  - アプリログが JSON 化され、CloudWatch Logs Insights の検索性が向上する。
  - ログ量増加に伴う CloudWatch 取り込みコスト増の可能性があるため、DEBUG 常時有効化は禁止運用とする。

## リスク
- 破壊的変更の可能性
  - API は不変だが、ログ追加に伴う性能劣化やログ量増加のリスクがある。
- 互換性への影響
  - ログフォーマット変更により、既存の文字列前提の運用手順があれば読み替えが必要になる。
- 運用影響
  - request 単位の MDC 解除漏れがあると、別リクエストへ文脈汚染するリスクがある。
- セキュリティ影響
  - `ownerSubject` 生値や token 情報の誤出力は重大。レビュー/テストで明示的に検知する。
- 監視影響
  - JSON フィールド名が不安定だと Logs Insights クエリが壊れるため、キー命名を固定する。

## 検証方針
- 単体テスト
  - `ownerSubject` ハッシュ関数の決定性・非可逆性（生値非一致）を検証する。
  - 監査ログ対象判定（書き込み操作のみ）を検証する。
- 結合テスト（MockMvc ベース）
  - `POST/PUT/DELETE` 実行時に監査ログキーが出力されること。
  - `GET/LIST` は監査ログ対象外であること。
  - 異常系で `WARN/ERROR` が使い分けされること。
  - `X-Amzn-Trace-Id` 受領時に `traceId` がログに反映されること。
- ログ出力検証
  - JSON 形式で出力されること（1 行 1 JSON）。
  - `ownerSubject` 生値がログに含まれないこと。
- コマンド
  - `cd backend && ./mvnw test`
  - 必要に応じて `./mvnw -DskipTests compile` でコンパイル確認。
- 手動確認（任意）
  - `spring-boot:run` + API 呼び出しでログ整形・相関ID出力を確認する。

## ドキュメント更新方針
- 新規追加
  - `docs/backend/` にログ設計ドキュメント（例: `logging.md`）を追加する。
  - 記載内容: ログ分類、ログレベル規約、JSON フィールド定義、相関ID、機密情報非出力、監査対象範囲、保持期間方針。
- 既存更新
  - `docs/backend/README.md` にログ設計ドキュメントへのリンクを追加する。
  - `backend/README.md` にログ方針の概要と詳細ドキュメント導線を追加する。
  - 必要なら `docs/README.md` の backend セクション導線を更新する。
- ADR 要否
  - 本件は既存観測基盤上の実装方針整理が中心のため、原則 ADR 追加不要。
  - ただし将来 OTel/DB監査まで拡張する正式方針を新規決定する場合は ADR 化を検討する。

## 実施順序
1. 事前設計の固定
   - JSON フィールド名、`eventType/action`、監査対象境界（書き込みのみ）を確定する。
2. ログ基盤の実装
   - `application.properties` に structured logging と動的ログレベル設定を追加する。
   - Request/MDC Filter と `ownerSubject` ハッシュ化コンポーネントを実装する。
3. 業務コードへの適用
   - Controller/Service/ExceptionHandler に `INFO/WARN/ERROR/DEBUG` を方針通り追加する。
   - 書き込み操作のみ監査ログを出力する。
4. テスト実装と調整
   - 単体/結合テストを追加し、機密値非出力と JSON キー整合を確認する。
5. ドキュメント反映
   - `docs/backend/` のログ設計ドキュメント作成と README 導線更新を行う。
6. 最終確認
   - 受け入れ条件（AC-01〜AC-13）に対して対応関係をチェックし、未実施があれば理由を明記する。

## 未解決事項
- なし（`specs.md` の未確定事項はすべて確定済み）。
- 実装時の確認点として、Spring Boot 4.0.5 で structured logging 設定と MDC 出力キーが意図どおり反映されることを検証で確認する。
