# Verification Report: 003-OTel-to-backend

## 実施コマンド

- `mvn -U -DskipTests compile`
- `mvn test`

## 結果

- 依存解決とソースコンパイル開始までは成功。
- ローカル実行環境の JDK が 21 未満のため、以下で停止。
  - `release version 21 is not supported`

## 未実施項目と理由

- Datadog 実環境での Logs/APM 相関確認
  - 理由: 本ローカル環境は ECS/Datadog 接続コンテキストを持たないため。

## 代替確認

- 実装方針・確認手順は `datadog-validation-checklist.md` に整理済み。
- JDK21 環境で `mvn test` 実行後、ECS 環境で checklist の観点を確認する。
