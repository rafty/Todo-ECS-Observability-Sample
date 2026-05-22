# Datadog Validation Checklist: 003-OTel-to-backend

## 前提

- `specs/004-Datadog-agent-to-cdk/specs-draft.md` の infra 実装が適用済みであること。
- ECS タスクへ `DD_SERVICE=todo-backend`, `DD_ENV`, `DD_VERSION`, `OTEL_*` が注入されていること。

## Logs 確認

1. Logs Explorer で以下クエリを実行する。
   - `service:todo-backend env:prod @trace_id:* @span_id:*`
2. ログイベントに次が存在することを確認する。
   - `trace_id`（32 文字小文字 hex）
   - `span_id`（16 文字小文字 hex）
   - `service`, `env`, `version`
3. `traceId` キー名が新規ログで使われていないことを確認する。

## Trace 相関確認

1. APM Trace 画面で `service:todo-backend env:prod` を検索する。
2. 任意 span から Logs タブへ遷移できることを確認する。
3. Logs 画面から View Trace in APM へ遷移できることを確認する。

## Datadog 側マッピング確認

1. `trace_id` が予約 Trace ID として認識されていることを確認する。
2. 自動認識されない場合は Preprocessing for JSON logs または Trace ID remapper を設定する。
3. 設定後に Logs ↔ APM 相関が成立することを再確認する。
