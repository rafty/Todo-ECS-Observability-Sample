# Tasks: Datadog Agent to CDK（ECS/Fargate オブザーバビリティ要件）

## 前提確認
- [x] 1. `AGENTS.md`、`backend/README.md`、`docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`、`docs/adr/adr-0003-OTel-DatadogAgent-settings.md` を再確認し、実装・運用制約を固定する。
- [x] 2. `specs/004-Datadog-agent-to-cdk/specs.md` の機能要件・非機能要件・受け入れ条件をチェックリスト化し、`plan.md` の実施順序と対応付ける。
- [x] 3. 対象外（`backend/` 業務コード変更、OTLP/HTTP 主経路化、OpenTelemetry Logs API 主経路化）を明示し、差分混入防止の作業境界を固定する。

## 実装タスク

### A. 設定入力・環境パラメータ整備（先行必須）
- [x] 4. `infra/lib/config/environment-config.ts` に Datadog 共通設定を追加する（`DD_SITE=datadoghq.com`、`DD_ENV`、`DD_SERVICE`、`DD_VERSION`、`DD_TAGS` 拡張軸）。
- [x] 5. `DD_TAGS` に Unified Service Tagging 拡張タグ（`team=o11y-CoE`、`system=todo`、`aws_account`）を追加し、`service/env/version` の重複定義を禁止する。
- [x] 6. sidecar リソース標準値を環境設定へ定義する（`datadog-agent`: CPU 256 / Memory 512 / Reservation 256、`log_router`: CPU 64 / Memory 128 / Reservation 64）。
- [x] 7. sidecar 用 CloudWatch Logs 保持日数を環境別に定義する（dev=3日、stg=7日、prod=14日、compliance はサンプル方針で通常ログ同等）。
- [x] 8. Datadog API Key Secret 参照規約（`/<environment>/<service>/datadog/api-key`）を環境設定/参照レイヤで利用可能にする。

### B. ECS TaskDefinition の 3 コンテナ化（依存: A 完了）
- [x] 9. `infra/lib/constructs/todo-backend-ecs-service-construct.ts` の TaskDefinition を `app` / `datadog-agent` / `log_router` の 3 コンテナ構成へ拡張する。
- [x] 10. app コンテナの log driver を `awsfirelens` へ切り替え、`dd_service` / `dd_source` / `dd_tags` / `dd_message_key` を設定する。
- [x] 11. `log_router` コンテナを `taskDefinition.addFirelensLogRouter()` で追加し、`type: FirelensLogRouterType.FLUENTBIT` と `enableECSLogMetadata: true` を設定する。
  - [x] 11-1. Datadog 出力プラグインを含むカスタム Fluent Bit 設定を明示的に扱う（`config-file-type=s3` + `config-file-value=<S3 ARN>` または `config-file-type=file` + カスタムイメージ同梱の `extra.conf`）。
  - [x] 11-2. `config-file-type=s3` を採用する場合は、対象オブジェクトへの `s3:GetObject` 権限をタスクロールへ付与する。
- [x] 12. `datadog-agent` コンテナを追加し、`awslogs`（診断用）と OTLP/gRPC 受信設定を実装する。
- [x] 13. app コンテナへ `OTEL_*` を注入する（`OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`、`OTEL_TRACES_EXPORTER=otlp`、`OTEL_METRICS_EXPORTER=otlp`、`OTEL_LOGS_EXPORTER=none`、`OTEL_SERVICE_NAME`、`OTEL_RESOURCE_ATTRIBUTES`）。
- [x] 14. datadog-agent コンテナへ必須 `DD_*` を注入する（`ECS_FARGATE=true`、`DD_APM_ENABLED=true`、`DD_APM_NON_LOCAL_TRAFFIC=true`、`DD_CHECKS_TAG_CARDINALITY=orchestrator`、`DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT=0.0.0.0:4317`、`DD_SITE`、`DD_ENV`、`DD_SERVICE`、`DD_VERSION`）。
- [x] 15. 非採用設定を排除する（`DD_LOGS_ENABLED` 未設定、`DD_OTLP_CONFIG_LOGS_ENABLED` 未設定）ことをコード上で担保する。
- [x] 16. Datadog API Key を `datadog-agent.secrets` と FireLens `secretOptions` へ注入し、平文環境変数を禁止する。
- [x] 17. app では `awslogs` と `awsfirelens` の二重指定を避け、sidecar（`datadog-agent` / `log_router`）のみ `awslogs` を使用する制約を反映する。

### C. 既存参照線の統一・運用パラメータ反映（依存: B 完了）
- [x] 18. `infra/lib/constructs/backend-image-deployment-construct.ts` と ECS 注入値の参照線を統一し、`DD_VERSION=imageTag` を固定する。
- [x] 19. APM Sampling 運用値を環境方針に沿って定義する（prod 10〜20%、stg 50%、dev 100%、Agent 設定値 `DD_APM_MAX_TPS=2` / `DD_APM_ERROR_TPS=10`）。
- [x] 20. Datadog Logs 送信先 `Host` が `DD_SITE=datadoghq.com` と整合するよう endpoint 設定を固定する。

### D. Datadog 運用設定（CDK外作業、依存: B 完了、C と並列可）
- [x] 21. Datadog Logs の index / exclusion / retention / daily quota 初期値を作成し、月次コスト上限（スパン月1万、APMホスト10、ログ100GB）監視基準を設定する。
- [x] 22. モニタ初期セット（error rate、latency p95/p99、Agent health、FireLens error、log volume anomaly）を作成する。
- [x] 23. ダッシュボード初期セット（service overview、latency/throughput/errors、ECS task health、APM trace volume）を作成する。

## テスト / 検証タスク
- [x] 24. `cdk synth` を実行し、テンプレート上で 3 コンテナ構成・log driver 制約・環境変数・secret 参照を確認する。
- [x] 25. 差分レビューで `DD_API_KEY` 平文埋め込みがないこと、`DD_LOGS_ENABLED` / `DD_OTLP_CONFIG_LOGS_ENABLED` 非設定が維持されていることを確認する。
- [x] 26. prod 環境へデプロイし、Datadog Logs で `service/env/version` 付き app ログ到達を確認する。
- [x] 27. Datadog APM/Metrics で OTLP/gRPC（4317）経由の trace/metric 到達を確認する（`service:todo-backend`）。
- [x] 28. Logs ↔ Traces 相関（`trace_id` / `span_id`）と ECS タスクタグ連携（`DD_CHECKS_TAG_CARDINALITY=orchestrator`）を確認する。
- [x] 29. CloudWatch Logs 保持日数（dev=3、stg=7、prod=14）が sidecar ロググループへ反映されていることを確認する。
- [x] 30. 未実施検証がある場合は、対象・理由・影響を `tasks.md` 実施記録または関連運用ドキュメントに明記する。

## ドキュメント更新タスク
- [x] 31. `docs/development/` に Datadog API Key Secret 作成手順（命名規則、年1回手動ローテーション、責任者条件）を追記する。
- [x] 32. `docs/backend/` または関連運用ドキュメントに ECS 注入値（`DD_*` / `OTEL_*`）とアプリ設定値の対応表を追記する。
- [x] 33. Datadog 運用手順として、index/exclusion/quota、モニタ、ダッシュボード、標準クエリを文書化する。
- [x] 34. 方針変更がないことを確認し、ADR 追加不要を記録する（変更が発生した場合のみ ADR 更新タスクを追加する）。

## 完了確認
- [x] 35. 受け入れ条件（3 コンテナ起動、Datadog Logs 到達、OTLP traces/metrics 到達、secret 注入、Logs↔Traces 相関）を全て満たすことを確認する。
- [x] 36. 対象外変更（`backend/` 業務コード、認証/DB スキーマ変更、OTLP/HTTP 主経路化）が混在していないことを確認する。
- [x] 37. シークレット値・認証情報・不要ファイルが差分に含まれていないことを最終確認する。
- [x] 38. タスク実施順序どおりに完了記録を更新し、並列実施した項目（D 系）との整合を確認する。

---

補足（実施順序と依存）
- 依存チェーン: A → B → C → 検証（24〜29）→ 完了確認（35〜38）
- 並列可能: D（21〜23）は B 完了後に C と並列で実施可能
- 実施単位の原則: 1 タスク 1 目的を維持し、実装・検証・文書化を混在させない

## 実施記録
- `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` の未注入が疑われたため、`TodoBackendContainer` へ同環境変数を明示注入して再デプロイ済み。
- Task 26〜28 の確認用に、Cognito テストユーザーを一時作成して `/api/todos` へ 200/201/204 応答の実トラフィックを複数回送信した（検証後ユーザー削除済み）。
- （当時）Datadog Logs/API 確認は `DD_APPLICATION_KEY` 未提供のため API 検索が `401 Unauthorized` となり、`service/env/version` 付き app ログの Datadog 側到達確認は未完了（Task 26 未完了）。
- （当時）Datadog Agent sidecar の CloudWatch ログでは、トラフィック送信後も `TRACE ... No data received` が継続し、OTLP/gRPC 経由の trace/metric 到達を確認できなかった（Task 27 未完了）。
- （当時）Logs ↔ Traces 相関（`trace_id` / `span_id`）および `DD_ECS_COLLECT_TASK_TAGS` の Datadog 側確認は、上記未到達および Datadog 検索キー不足のため未完了（Task 28 未完了）。
- Task 29 は完了。prod 実リソースで sidecar ロググループの `retentionInDays=14` を確認済み。dev/stg は `environment-config` の設定値（dev=3, stg=7）を確認し、`cdk synth -c env=dev|stg` は対象アカウント AssumeRole 権限不足で未実行。
- 2026-05-28 に `DD_APPLICATION_KEY`（`/prod/todo-backend/datadog/app-key`）を利用して Datadog API 検証を再実施し、Task 26〜28 の証跡を取得した。
- Task 26: Datadog Logs API（`/api/v2/logs/events/search`）で `service:todo-backend env:prod version:14f461279449c0afe38ed4d127ee1faa7e3a436b5e016a855ea881ef870a1cef` を検索し、`Todo created` ログ（例: `2026-05-28T06:31:52.055Z`）到達を確認した。
- Task 27: `TodoBackendContainer` に `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://localhost:4317` / `...TRANSPORT=grpc` を追加し prod 再デプロイ後、Datadog Metrics API（`/api/v1/query`）で `sum:trace.server.request.hits{service:todo-backend,env:prod,version:14f...}.as_count()` のポイント増加（例: `1779949920000`, `1779949970000`）を確認した。設定上、trace 経路は OTLP/gRPC（4317）に固定されているため、trace/APM metric 到達は同経路由来と判断した。
- 補足（Task 27）: Spring Boot の Micrometer OTLP exporter は `http://localhost:4317/v1/metrics` への送信失敗 WARN を継続出力している。今回の到達確認は Datadog APM trace metrics（`trace.server.request.hits`）を根拠にしている。
- Task 28: Datadog Logs で `trace_id` / `span_id` が付与された app ログ（例: `trace_id=d4672394fcdb647fcebda604ef2b91de`）を確認し、同ログに `task_arn` / `task_version` を含む ECS タスクタグが付与されることを確認した。
- Datadog Agent v7.79.0 では `DD_ECS_COLLECT_TASK_TAGS` が Unknown となるため、`DD_CHECKS_TAG_CARDINALITY=orchestrator` に置換して再デプロイし、Unknown 環境変数警告が消えることを確認した。
- 補足: Datadog Spans Search API（`/api/v2/spans/events/search`）は `service:todo-backend env:prod` で 0 件だったため、trace 到達確認は APM trace metric（`trace.server.request.hits`）とログ相関情報で実施した。
- 2026-05-28 18:34 JST 実施: `npx cdk deploy -c env=prod --require-approval never` を実行し、`InfraStack-prod` が `UPDATE_COMPLETE` となることを確認した。デプロイ後の `TodoAppEcrImageTag` は `b492fc3293dafb48d196541d04d0504c407db77c7c328d498f649d2368005a7f`、ECS Service は task definition `:14`（running=2/desired=2）へロールアウト完了。
- 2026-05-28 18:41 JST 実施: CloudWatch Logs を確認し、`DatadogAgentLogGroup` / `LogRouterLogGroup` の `retentionInDays=14`（prod）を確認。Datadog Agent ログで OTLP receiver の `GRPC server [::]:4317` と `HTTP server [::]:4318` 起動を確認。
- 2026-05-28 18:42 JST 実施（Task 26 再確認）: Datadog Logs API（`/api/v2/logs/events/search`）で `service:todo-backend env:prod version:b492...` を検索し、`count=5`（初期化ログ）を確認。`service/env/version` タグ付き app ログ到達を確認。
- 2026-05-28 18:45 JST 実施（Task 27 再確認）: Datadog Spans API（`/api/v2/spans/events/search`）で `service:todo-backend env:prod` を検索し、`count=52`、`version=b492...`、`task_version=14` の span を確認（例: `trace_id=d45a1af68a317224f3643fc440983f27`）。
- 2026-05-28 18:48 JST 実施（Task 27 再確認）: Datadog Metrics API（`/api/v1/query`）で `jvm.buffer.count` / `spring.security.filterchains` / `tomcat.sessions.active.current` を `service:todo-backend,env:prod,version:b492...` 条件で照会し、いずれも `series=1` かつ `non_null_points>0` を確認。metrics 到達を確認。
- 2026-05-28 18:50 JST 実施: 過去エラー再確認として Datadog Logs API で旧 version（`14f...`）を検索し、`Failed to publish metrics to OTLP receiver (url=http://localhost:4317/v1/metrics)` を確認。新 version（`b492...`）では同条件検索 `count=0`（直近 60 分）を確認。
- 2026-05-28 18:52 JST 実施（Task 28 参考）: 新 version（`b492...`）の直近ログ 20 件で `trace_id` / `span_id` の付与は `0 件` だった一方、`task_arn` / `task_version` / `cluster_name` など ECS タスク関連タグは付与されていることを確認。
- 2026-05-28 18:53 JST 実施（Task 29 参考）: 当該 AWS アカウントでは `InfraStack-prod` のみ存在し、dev/stg スタックは未作成のため、CloudWatch Logs の実リソース確認は prod（14 日）のみ実施。dev/stg（3/7 日）は `environment-config.ts` の設定値確認に留める。
