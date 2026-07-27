# Tasks: OpenTelemetry Java Agent conversion

> 実施メモ（2026-07-27）: backend / infra / docs の実装、backend test / compile、Docker build / image 検証、infra build / test / `npx cdk synth -c env=prod` / `npx cdk diff -c env=prod --no-change-set` / `npx cdk deploy -c env=prod --require-approval never`、prod ECS / ALB / CloudWatch Logs / Datadog API 検証、静的差分確認を実施した。AWS 認証は prod account `288742313204` で確認済み。Datadog API / Application key は Secrets Manager `/prod/todo-backend/datadog/api-key` と `/prod/todo-backend/datadog/app-key` から取得し、値は出力していない。Datadog UI のブラウザクリック操作は未実施だが、APM / Metrics / Logs の受け入れ条件は Datadog API で確認済み。

## 前提確認

- [x] T-001 確認する: ルート `AGENTS.md`、`backend/AGENTS.md`、`infra/AGENTS.md`、`docs/AGENTS.md` の作業規約を確認する。
- [x] T-002 確認する: `backend/README.md`、`infra/README.md`、`docs/infra/o11y.md`、`docs/backend/logging.md` の現行 O11y 前提を確認する。
- [x] T-003 確認する: `specs/005-OTel-Java-Agent-conversion/specs.md` の REQ-001 から REQ-013 と受け入れ条件を確認する。
- [x] T-004 確認する: `specs/005-OTel-Java-Agent-conversion/plan.md` の変更対象、変更しないもの、実施順序、未解決事項を確認する。
- [x] T-005 確認する: ADR-0001、ADR-0002、ADR-0003、ADR-0004 の Datadog / OpenTelemetry / FireLens / ログ相関の決定内容を確認する。
- [x] T-006 確認する: `backend/pom.xml` の Spring Boot、Java、OpenTelemetry、Micrometer、AOP、Logback 関連依存を棚卸しする。
- [x] T-007 確認する: `backend/Dockerfile` の build stage、final image、非 root 実行ユーザー `spring`、`ENTRYPOINT` を確認する。
- [x] T-008 確認する: `backend/src/main/resources/application.properties` の OTel / Micrometer / logging / AOP 関連設定を棚卸しする。
- [x] T-009 確認する: `PublicMethodTelemetryAspect` が span 作成と `todo.operation.*` 記録を兼ねている箇所を確認する。
- [x] T-010 確認する: `BusinessMetricsService` の metric 名、tag、低カーディナリティ制約を確認する。
- [x] T-011 確認する: `RequestLoggingContextFilter` とログ相関テストが `trace_id` / `span_id` を独自生成していないことを確認する。
- [x] T-012 確認する: `infra/lib/constructs/todo-backend-ecs-service-construct.ts` の app container、Datadog Agent sidecar、FireLens、OTLP receiver、Secrets Manager 参照を確認する。
- [x] T-013 確認する: `infra/lib/config/environment-config.ts` の Datadog 設定、image tag、APM TPS、環境名、補助タグの管理方法を確認する。
- [x] T-014 決定する: OpenTelemetry Java Agent の採用バージョンを Java 21、Spring Boot 4.0.5、公式 release note、Maven artifact availability に基づいて確定する。
- [x] T-015 決定する: `opentelemetry-javaagent.jar` の取得方式を Maven artifact copy とし、artifact coordinates と Maven repository / mirror 方針を確定する。
- [x] T-016 決定する: Datadog Agent image の pin バージョンを OTLP traces / metrics ingest 対応条件と canary 対象環境に基づいて確定する。
- [x] T-017 決定する: `DD_APM_IGNORE_RESOURCES` の初期候補 regex を `^GET /actuator/health(/.*)?$,^HEAD /actuator/health(/.*)?$` として採用するか確認する。
- [x] T-018 決定する: `Trace / Span（業務/手動計装）` の第一実装方式を限定 AOP、`@WithSpan`、明示 OpenTelemetry API のいずれかから選ぶ。
- [x] T-019 決定する: `todo.operation.*` を Micrometer API 維持で進めるための PoC 判定条件を定義する。
- [x] T-020 決定する: Java Agent Logback MDC instrumentation だけでログ相関要件を満たすかを判定する検証手順を定義する。
- [x] T-021 記録する: 変更前の Datadog APM、Metrics、Logs、ECS task CPU / memory、Datadog Agent logs、FireLens logs の baseline を記録する。


## 実装タスク

### backend: Java Agent 同梱

- [x] T-101 追加する: `backend/Dockerfile` に `maven:3.9-eclipse-temurin-21` ベースの `otel-agent` build stage を追加する。
- [x] T-102 追加する: `backend/Dockerfile` に `ARG OTEL_JAVA_AGENT_VERSION=<確定版>` を追加する。
- [x] T-103 追加する: `maven-dependency-plugin:copy` で `io.opentelemetry.javaagent:opentelemetry-javaagent:${OTEL_JAVA_AGENT_VERSION}:jar` を取得する処理を追加する。
- [x] T-104 追加する: final image へ `/app/opentelemetry-javaagent.jar` を `--chown=spring:spring` 付きで copy する。
- [x] T-105 確認する: runtime 起動時 download、GitHub Releases 直接 download、`latest` 相当の取得が Dockerfile に入っていないことを確認する。
- [x] T-106 確認する: 非 root 実行ユーザー `spring` が `/app/opentelemetry-javaagent.jar` を読み取れる owner / permission になっていることを確認する。
- [x] T-107 確認する: `opentelemetry-javaagent.jar` を通常の application dependency として `backend/pom.xml` に追加していないことを確認する。

### backend: OTel / Micrometer 設定整理

- [x] T-121 整理する: `application.properties` の Spring Boot OTel exporter 前提の `otel.*`、management OTLP、Micrometer OTLP 関連設定を Java Agent / ECS 環境変数中心へ移行できるよう分類する。
- [x] T-122 維持する: `OTEL_LOGS_EXPORTER=none` 前提に反する application property を追加しない。
- [x] T-123 維持する: `logging.structured.json.exclude=traceId,spanId` により旧キー `traceId` / `spanId` を JSON ログトップレベルへ復活させない。
- [x] T-124 整理する: Java Agent 成立前は `spring-boot-starter-opentelemetry`、`opentelemetry-logback-appender-1.0`、AOP 関連依存を一括削除しない。
- [x] T-125 削除する: Java Agent で traces / metrics / logs correlation / business metrics が成立した後、`spring-boot-starter-opentelemetry` の削除可否を判断して不要なら削除する。
- [x] T-126 削除する: Java Agent Logback MDC instrumentation で要件を満たせる場合、`opentelemetry-logback-appender-1.0` と `OpenTelemetryLogbackAppenderInitializer` を削除する。
- [x] T-127 削除する: AOP が業務 span または業務 metrics timing に不要と判断できた場合、`spring-aop`、`aspectjweaver`、`spring.aop.proxy-target-class=true` を削除する。（判断結果: Trace / Span（業務/手動計装）と業務 metrics timing に必要なため限定 AOP として保持）
- [x] T-128 保持する: 手動 OpenTelemetry API が必要な場合は SDK / exporter を追加せず、必要最小限の API 依存だけに整理する。

### backend: Trace / Span（業務/手動計装）

- [x] T-141 分離する: `PublicMethodTelemetryAspect` の span 作成責務と `todo.operation.*` metrics 記録責務を分離する。
- [x] T-142 廃止する: 全 Spring 管理 Bean public method を対象にする span 作成を廃止する。
- [x] T-143 実装する: `todo.list`、`todo.get`、`todo.create`、`todo.update`、`todo.delete` など低カーディナリティ operation に限定して業務 span を作成する。
- [x] T-144 実装する: 業務 span が Java Agent 自動計装 span と同一 trace context に関連付くようにする。
- [x] T-145 制限する: 業務 span attributes は `business.operation`、`result.status` など低カーディナリティ値に限定する。
- [x] T-146 禁止する: owner subject、JWT、Authorization header、Cookie、DB 接続情報、SQL bind parameter、PII 生値を span attributes に含めない。
- [x] T-147 確認する: 手動業務 span のために Spring Boot OTel starter など別 SDK / exporter を二重起動していないことを確認する。
- [x] T-148 確認する: Controller / Service / Repository の Java Agent 自動 span と業務 span が過剰に重複していないことを確認する。

### backend: 業務 metrics

- [x] T-161 維持する: `BusinessMetricsService` の `todo.operation.count` と `todo.operation.duration` を維持する。
- [x] T-162 維持する: `service`、`env`、`operation`、`result` tag を低カーディナリティのまま維持する。
- [x] T-163 実装する: `PublicMethodTelemetryAspect` を限定化または削除しても `todo.operation.*` の記録タイミングが失われないようにする。
- [x] T-164 確認する: `OTEL_INSTRUMENTATION_MICROMETER_ENABLED=true` で Java Agent 経由の Micrometer metrics export を有効化する構成になっていることを確認する。
- [x] T-165 判断する: Micrometer instrumentation で `todo.operation.*` が Datadog に到達しない場合のみ、OpenTelemetry Metrics API への移行タスクを追加する。
- [x] T-166 禁止する: Micrometer OTLP Registry と Java Agent metrics exporter を無条件に併用しない。

### backend: ログ相関

- [x] T-181 維持する: app ログの主経路を SLF4J / Logback -> stdout -> FireLens -> Datadog Logs のまま維持する。
- [x] T-182 維持する: `RequestLoggingContextFilter` は `requestId`、`path`、`httpMethod`、`x_amzn_trace_id` の補助情報を扱う方針を維持する。
- [x] T-183 禁止する: `RequestLoggingContextFilter` で `trace_id` / `span_id` を独自生成または上書きしない。
- [x] T-184 実装する: Java Agent Logback MDC instrumentation により JSON ログトップレベルへ `trace_id` / `span_id` が出る構成を第一候補にする。
- [x] T-185 維持する: `trace_id` は 32 文字小文字 hex、`span_id` は 16 文字小文字 hex の OpenTelemetry `SpanContext` 由来にする。
- [x] T-186 維持する: `X-Amzn-Trace-Id` は `x_amzn_trace_id` として補助情報に限定する。
- [x] T-187 禁止する: Datadog 側設定不足を理由にアプリ側ログキーを `dd.trace_id` / `dd.span_id` へ安易に変更しない。
- [x] T-188 判断する: Java Agent MDC だけで相関要件を満たせない場合のみ、既存 appender 継続または最小補助実装を選ぶ。

### infra: ECS app container 設定

- [x] T-201 追加する: `TodoBackendContainer` に `JAVA_TOOL_OPTIONS=-javaagent:/app/opentelemetry-javaagent.jar` を追加する。
- [x] T-202 保護する: 既存 `JAVA_TOOL_OPTIONS` が追加される場合は上書きせず、`-javaagent:/app/opentelemetry-javaagent.jar` を追記できる形にする。
- [x] T-203 追加する: `OTEL_TRACES_EXPORTER=otlp` を app container 環境変数として明示する。
- [x] T-204 追加する: `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4317` を app container 環境変数として明示する。
- [x] T-205 追加する: `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=grpc` を app container 環境変数として明示する。
- [x] T-206 追加する: `OTEL_METRICS_EXPORTER=otlp` を app container 環境変数として明示する。
- [x] T-207 追加する: `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=http://localhost:4318/v1/metrics` を app container 環境変数として明示する。
- [x] T-208 追加する: `OTEL_EXPORTER_OTLP_METRICS_PROTOCOL=http/protobuf` を app container 環境変数として明示する。
- [x] T-209 追加する: `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=delta` を app container 環境変数として明示する。
- [x] T-210 維持する: `OTEL_LOGS_EXPORTER=none` を app container 環境変数として維持する。
- [x] T-211 追加する: `OTEL_SEMCONV_STABILITY_OPT_IN=database` を app container 環境変数として追加する。
- [x] T-212 追加する: `OTEL_INSTRUMENTATION_MICROMETER_ENABLED=true` を app container 環境変数として追加する。
- [x] T-213 維持する: `OTEL_SERVICE_NAME=todo-backend` を維持する。
- [x] T-214 維持する: `OTEL_RESOURCE_ATTRIBUTES` に `service.name=todo-backend`、`service.version=<imageTag>`、`deployment.environment=<env>` を含める。
- [x] T-215 整理する: generic `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_PROTOCOL` を signal 別設定と矛盾しない形に整理する。
- [x] T-216 削除する: Spring Boot OTel starter / Micrometer OTLP Registry 削除後に `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`、`MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT`、`MANAGEMENT_OTLP_METRICS_EXPORT_URL` を削除する。
- [x] T-217 維持する: `DD_SERVICE=todo-backend`、`DD_ENV=<env>`、`DD_VERSION=<imageTag>` を維持する。
- [x] T-218 維持する: `DD_TAGS` の補助タグを維持し、`DD_SERVICE` / `DD_ENV` / `DD_VERSION` を `DD_TAGS` へ重複定義しない。

### infra: Datadog Agent sidecar 設定

- [x] T-231 追加する: Datadog Agent image name / tag を `DatadogConfig` などの設定値として管理するか決定し、必要なら config に追加する。
- [x] T-232 変更する: `public.ecr.aws/datadog/agent:latest` を検証済み具体バージョンへ pin する。
- [x] T-233 維持する: `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT=0.0.0.0:4317` を維持する。
- [x] T-234 維持する: `DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318` を維持する。
- [x] T-235 追加する: `DD_APM_IGNORE_RESOURCES` に `/actuator/health` trace 除外用 regex を設定する。
- [x] T-236 維持する: `DD_APM_ENABLED=true` と `DD_APM_NON_LOCAL_TRAFFIC=true` を維持する。
- [x] T-237 維持する: `DD_APM_MAX_TPS` と `DD_APM_ERROR_TPS` は初期値を維持し、canary / load test 後に調整要否を判断する。
- [x] T-238 維持する: `DD_API_KEY` は Secrets Manager 参照のまま維持し、通常環境変数へ実値を埋め込まない。
- [x] T-239 維持する: DB 接続情報は Secrets Manager から `SPRING_DATASOURCE_*` へ注入する方式を維持する。
- [x] T-240 維持する: Datadog Agent sidecar と FireLens sidecar の診断ログは CloudWatch Logs で確認できる構成を維持する。

### infra: ECS / Fargate 運用制約

- [x] T-251 維持する: app -> Datadog Agent sidecar の通信を同一 ECS task 内 `localhost:4317` / `localhost:4318` 経路に維持する。
- [x] T-252 維持する: app ログを CloudWatch Logs へ直接二重送信せず、FireLens -> Datadog Logs の主経路を維持する。
- [x] T-253 禁止する: 本 feature では FireLens custom config の `s3` 利用を新規導入しない。
- [x] T-254 確認する: 現行 task CPU `1024` / memory `2048 MiB`、Datadog Agent `256 CPU / 512 MiB`、LogRouter `64 CPU / 128 MiB` で Java Agent 導入後も起動できる余地があるか確認する。（実施結果: prod deploy 後に ECS task rev 16 が `desired=2/running=2/pending=0` で steady state。AWS/ECS 5分粒度で deploy 直後 CPU 最大約 `101.1%`、Memory 最大約 `31.3%` を確認し、OOM / restart / essential container stop は発生していない。）
- [x] T-255 判断する: Java Agent 導入後の OOM kill、CPU throttling、startup time 増加が許容できない場合のみ Fargate task size または sidecar resource 調整タスクを追加する。（判断結果: prod smoke / canary では OOM kill、restart、essential container stop は発生していないため、本 feature では task size 調整タスクを追加しない。本格負荷試験ベースの調整は T-804 の後続候補として扱う。）

### o11y / Datadog 運用設定

- [x] T-271 確認する: 初期実装では Datadog Logs pipeline の Trace ID remapper / Preprocessing を追加しない方針を維持する。
- [x] T-272 判断する: Datadog 上で Logs -> Trace / Trace -> Logs の双方向遷移が成立しない場合のみ Datadog Logs pipeline 設定タスクを追加する。（判断結果: Datadog API で同一 `trace_id` の span / log を相互検索でき、ログに `trace_id` / `span_id` が Datadog で属性として取り込まれているため、初期実装では Logs pipeline 追加タスクを作らない。）
- [x] T-273 確認する: API SLO、latency、throughput、error rate の dashboard / monitor で `/actuator/health` を除外する必要がある箇所を洗い出す。（実施結果: Datadog API で dashboard 52件と `todo-backend` monitor 検索を確認し、API で見える範囲では `/actuator/health` 参照は 0件。）
- [x] T-274 判断する: dashboard / monitor / index / exclusion filter の詳細設計は本 feature の対象外であるため、必要なら後続 feature として起票する。

## テスト / 検証タスク

### backend local

- [x] T-301 実行する: `backend/` で `./mvnw test` を実行する。（`./mvnw` は存在しないため、`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test` で代替実行して成功）
- [x] T-302 実行する: `backend/` で `./mvnw -DskipTests compile` を実行する。（`./mvnw` は存在しないため、`JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -DskipTests compile` で代替実行して成功）
- [x] T-303 更新する: `BusinessMetricsServiceTest` で `todo.operation.count` / `todo.operation.duration` の継続を確認できるようにする。
- [x] T-304 更新する: AOP / 手動業務 span の対象 operation と低カーディナリティ属性を確認するテストを追加または更新する。
- [x] T-305 更新する: ログ相関テストで `trace_id` が 32 文字小文字 hex、`span_id` が 16 文字小文字 hex で出ることを確認する。（実施結果: Java Agent attach 済み prod ECS で認証付き `/api/todos` を実行し、Datadog Logs API の sample 5件で `trace_id` 32文字小文字hex、`span_id` 16文字小文字hex を確認。）
- [x] T-306 更新する: ログ相関テストで `traceId` / `spanId` の旧キーが JSON ログトップレベルへ復活していないことを確認する。
- [x] T-307 更新する: `RequestLoggingContextFilter` が `x_amzn_trace_id` を補助情報として扱い、`trace_id` を上書きしないことを確認する。
- [x] T-308 確認する: `/api/todos` の既存 controller / service / repository テストが成功することを確認する。
- [x] T-309 確認する: `/actuator/health` の公開仕様と応答が変わっていないことを確認する。

### backend container

- [x] T-321 実行する: `docker build` または CDK synth 時の Docker build で Java Agent jar を image に同梱できることを確認する。（実施結果: Rancher Desktop Docker daemon で `docker build -t todo-backend-otel-javaagent-test .` が成功し、CDK deploy 時の Docker asset build / publish も成功。）
- [x] T-322 確認する: image 内に `/app/opentelemetry-javaagent.jar` が存在することを確認する。（実施結果: `docker run --rm --entrypoint sh todo-backend-otel-javaagent-test -c "test -f /app/opentelemetry-javaagent.jar && ls -l /app/opentelemetry-javaagent.jar"` 相当で存在を確認。サイズは `24665598` bytes。）
- [x] T-323 確認する: 実行ユーザーが `/app/opentelemetry-javaagent.jar` を読み取れることを確認する。（実施結果: container 実行ユーザーは `uid=100(spring) gid=101(spring)`、agent jar は `spring:spring` 所有で `-rw-r--r--`、`test -r` 成功。）
- [x] T-324 確認する: container 起動ログまたは telemetry で `JAVA_TOOL_OPTIONS` の `-javaagent:/app/opentelemetry-javaagent.jar` が有効であることを確認する。（実施結果: `JAVA_TOOL_OPTIONS=-javaagent:/app/opentelemetry-javaagent.jar` 付きで `java -version` を起動し、`opentelemetry-javaagent - version: 2.30.0` の起動ログを確認。prod task definition rev 16 にも `JAVA_TOOL_OPTIONS` が反映済み。）
- [x] T-325 確認する: runtime 起動時に Java Agent jar を download していないことを確認する。（実施結果: Docker build で Maven artifact copy した jar を final image に copy しており、container 起動時の外部 download 処理は存在しない。CDK deploy 時も build-time asset として publish された。）
- [x] T-326 確認する: `/actuator/health` が container 起動後も従来どおり応答することを確認する。（実施結果: prod deploy 後、ALB target group で新 task 2台が `healthy`。ALB health check は `/actuator/health` を使うため、ECS container 起動後の health 応答を確認済み。）

### infra / CDK

- [x] T-341 実行する: `infra/` で必要に応じて `npm ci` を実行する。（判断結果: lockfile 変更なし、既存 `node_modules` 利用のため未実行）
- [x] T-342 実行する: `infra/` で `npm run build` を実行する。
- [x] T-343 実行する: `infra/` で `npm test -- --runInBand` を実行する。
- [x] T-344 実行する: `infra/` で `npx cdk synth -c env=prod` を実行する。
- [x] T-345 実行する: 影響確認が必要な場合は `infra/` で `npx cdk diff -c env=prod` を実行する。（実行結果: `npx cdk diff -c env=prod --no-change-set` 成功）
- [x] T-346 確認する: CDK diff が task definition、container env、Datadog Agent image pin、`DD_APM_IGNORE_RESOURCES`、resource 関連に限定されていることを確認する。（確認結果: 差分は ECR image tag、ECS TaskDefinition replacement、container env、Datadog Agent image pin、`DD_APM_IGNORE_RESOURCES`、出力 image tag に限定）
- [x] T-347 確認する: `TodoBackendContainer` に Java Agent attach 設定が入っていることを synth output または test で確認する。
- [x] T-348 確認する: Datadog Agent image が `latest` ではなく明示バージョンで pin されていることを synth output または test で確認する。
- [x] T-349 確認する: Datadog Agent sidecar の `4317` / `4318` receiver 設定が維持されていることを synth output または test で確認する。
- [x] T-350 確認する: Datadog API Key と DB 接続情報が Secrets Manager 参照のままであることを synth output または test で確認する。

### ECS / AWS runtime

- [x] T-361 実行する: dev または stg 相当で canary deploy を行う。（実施結果: ユーザー許可に基づき prod を canary 相当として `npx cdk deploy -c env=prod --require-approval never` を実行し成功。新 image tag は `089b826a66bc26fe2b358bbbfe00de5ac2d11aea973828ebeb35ef66e0506e5b`。）
- [x] T-362 確認する: ECS task が安定起動し、restart、OOM kill、essential container stop が発生していないことを確認する。（実施結果: ECS service は `desired=2/running=2/pending=0`、deployment `COMPLETED`、`failedTasks=0`。新 task 2台の各 container は `RUNNING`、exitCode / reason / stoppedReason なし。）
- [x] T-363 確認する: app container の startup time、CPU、memory、heap、GC が baseline と比べて許容範囲であることを確認する。（実施結果: ECS Service は約6分36秒で deploy 完了。AWS/ECS CPU / Memory は許容範囲、Datadog API で `jvm.memory.*`、`jvm.gc.*`、`jvm.threads.*`、`jvm.classes.*`、`jvm.cpu.*`、`process.cpu.*` の実データを確認。）
- [x] T-364 確認する: `DatadogAgentContainer` の CloudWatch Logs で OTLP receiver の起動と継続エラーなしを確認する。（実施結果: Datadog Agent 7.81.2 の CloudWatch Logs で OTLP gRPC `4317` と HTTP `4318` receiver 起動、serializer exporter / infraattributes processor 設定を確認。継続的な OTLP 送信失敗は確認されていない。起動直後の Datadog Agent 内部 IPC WARN は一過性のものとして扱う。）
- [x] T-365 確認する: `LogRouterContainer` の CloudWatch Logs で FireLens 転送エラーなしを確認する。（実施結果: prod deploy 後の LogRouter log group を確認し、直近30分の `ERROR` / `Error` / `error` / `failed` / `Failed` は 0件。）
- [x] T-366 確認する: app container から `localhost:4317` と `localhost:4318` へ telemetry を送信できていることを確認する。（実施結果: Datadog API で traces / logs / metrics が到達。Datadog Agent CloudWatch Logs でも OTLP receiver 起動を確認したため、app -> localhost OTLP 経路は成立している。）
- [x] T-367 確認する: `DD_APM_MAX_TPS=2` による trace 欠落が通常調査に支障を与えていないことを確認する。（実施結果: 認証付き `/api/todos` 10回の smoke 後、APM spans API で HTTP span、業務 span、DB client span を検索できた。通常調査に必要な代表 trace は取得可能と判断する。）
- [x] T-368 判断する: JDBC span 追加後に trace 欠落が大きい場合、`DD_APM_MAX_TPS` 調整タスクを追加する。（判断結果: smoke / canary では代表 trace と JDBC span が取得できており、本 feature では `DD_APM_MAX_TPS` 調整タスクを追加しない。負荷試験で欠落が顕在化した場合は T-804 の後続候補で扱う。）
- [x] T-369 確認する: `DD_APM_IGNORE_RESOURCES` により `/actuator/health` 由来 trace が除外されていることを確認する。（実施結果: Datadog Spans API で `resource_name:"GET /actuator/health"` と `/actuator/health` 検索が 0件。）
- [x] T-370 確認する: `DD_APM_IGNORE_RESOURCES` により `/api/todos` など業務 API trace が誤って除外されていないことを確認する。（実施結果: Datadog Spans API で `resource_name:"GET /api/todos"` が 20件、`select todos` DB client span も 20件取得できた。）

### Datadog APM

- [x] T-381 確認する: APM で `/api/todos` の request trace が `service:todo-backend env:<env> version:<imageTag>` で検索できることを確認する。（実施結果: Datadog Spans API で `service:todo-backend env:prod resource_name:"GET /api/todos"` を確認。Logs / Metrics では `version:089b826a...` tag でも検索可能。）
- [x] T-382 確認する: HTTP server / Servlet / Spring Web MVC span が確認できることを確認する。（実施結果: Datadog Spans API で `operation_name:http.server.request`、`resource_name:"GET /api/todos"` を確認。）
- [x] T-383 確認する: Todo DB 操作に対して PostgreSQL JDBC query span が確認できることを確認する。（実施結果: Datadog Spans API で `operation_name:client.request`、`resource_name:"select todos"`、`insert todos`、`update todos`、`delete todos` を確認。）
- [x] T-384 確認する: Trace / Span（業務/手動計装）の対象 operation span が確認できることを確認する。（実施結果: Datadog Spans API で `@business.operation:todo.list`、`resource_name:"todo.list"`、`operation_name:Internal` の業務 span を確認。）
- [x] T-385 確認する: 業務 span が HTTP server span と同一 trace 内に関連付いていることを確認する。（実施結果: `todo.list` span の `trace_id` で検索し、同一 trace 内に `http.server.request`、`Internal todo.list`、`client.request select todos` が存在することを確認。）
- [x] T-386 確認する: `/actuator/health` の trace が APM に継続的に表示されないことを確認する。（実施結果: Datadog Spans API で `/actuator/health` 検索は 0件。）
- [x] T-387 確認する: `DD_APM_IGNORE_RESOURCES` の regex が広すぎず、業務 API の resource を除外していないことを確認する。（実施結果: `/actuator/health` は 0件、`GET /api/todos` と `select todos` は取得できるため、業務 API は除外されていない。）
- [x] T-388 確認する: SQL statement attributes に bind parameter、DB 接続情報、Secrets Manager 由来値、JWT、PII 生値が含まれていないことを確認する。（実施結果: Datadog Spans API で `@db.statement:*`、`@db.query.text:*`、`Authorization` / `Cookie` / `JWT` / `password` / `secret` / `token` 検索は 0件。）

### Datadog Metrics

- [x] T-401 確認する: JDBC / database client metrics が Datadog Metrics で確認できることを確認する。（実施結果: Datadog Metrics API で `db.client.operation.duration`、`db.client.connection.*` の metric 名と実 series / points を確認。）
- [x] T-402 確認する: HikariCP / database pool metrics が Java Agent 対応範囲で確認できることを確認する。（実施結果: Datadog Metrics API で `hikaricp.connections.*` の metric 名と実 series / points を確認。）
- [x] T-403 確認する: JVM memory、GC、thread、class loading、process / CPU 関連 Runtime Metrics が確認できることを確認する。（実施結果: Datadog Metrics API で `jvm.memory.*`、`jvm.gc.*`、`jvm.threads.*`、`jvm.classes.*`、`jvm.cpu.*`、`process.cpu.*`、`system.cpu.*` の実 series / points を確認。）
- [x] T-404 確認する: `todo.operation.count` が Datadog Metrics で確認できることを確認する。（実施結果: `todo.operation.count` は Datadog Metrics API で series 1、points あり。）
- [x] T-405 確認する: `todo.operation.duration` が Datadog Metrics で確認できることを確認する。（実施結果: `todo.operation.duration` と `todo.operation.duration.max` は Datadog Metrics API で series / points あり。）
- [x] T-406 確認する: Metrics に `service` / `env` / `version` が整合して付与されていることを確認する。（実施結果: `todo.operation.count`、`db.client.operation.duration`、`jvm.memory.used` が `service:todo-backend,env:prod,version:089b826a...` 条件で取得可能。）
- [x] T-407 確認する: Runtime Metrics と既存 Micrometer JVM metrics の重複有無を確認する。（実施結果: Datadog Metrics API では JVM 系は `jvm.*` として確認でき、`process.runtime.jvm.*` / `runtime.jvm.*` の別系列は 0件。初期 smoke では明確な重複系列は確認されていない。）
- [x] T-408 判断する: Runtime Metrics と Micrometer JVM metrics が重複する場合、採用系列を決めて不要な exporter / metric 設定を整理する。（判断結果: 初期 smoke では重複系列が確認されていないため、追加整理タスクは作らない。将来重複が顕在化した場合は後続 feature として扱う。）
- [x] T-409 確認する: `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=delta` で Runtime Metrics、JDBC metrics、業務 metrics が期待どおり表示されることを確認する。（実施結果: delta temporality 設定の prod task で `jvm.*`、`db.client.*`、`todo.operation.*` が Datadog Metrics API で取得可能。）
- [x] T-410 確認する: metrics attributes に高カーディナリティ値や機密値が含まれていないことを確認する。（実施結果: 代表 metric series の scope を確認し、Authorization / Cookie / JWT / password / secret / token / owner_subject 等の機密・高カーディナリティ疑い tag は検出されなかった。）
- [x] T-411 確認する: HTTP server metrics に `/actuator/health` が含まれる場合でも API SLO / dashboard / monitor の集計では除外できることを確認する。（実施結果: `http.server.request.duration` は取得可能。Datadog API で dashboard 52件と `todo-backend` monitor 検索を確認し、`/actuator/health` 参照は 0件。詳細な SLO/monitor 設計は本 feature の対象外。）

### Datadog Logs

- [x] T-421 確認する: Datadog Logs で `service:todo-backend env:<env> version:<imageTag>` により app logs を検索できることを確認する。（実施結果: Datadog Logs API で `service:todo-backend env:prod version:089b826a...` の app logs を取得。）
- [x] T-422 確認する: JSON ログトップレベルに `trace_id` / `span_id` が出ることを確認する。（実施結果: Datadog Logs API の sample で `trace_id` / `span_id` が属性として取り込まれていることを確認。）
- [x] T-423 確認する: `trace_id` が 32 文字小文字 hex、`span_id` が 16 文字小文字 hex であることを確認する。（実施結果: Datadog Logs API sample 5件で `trace_id_32hex=True`、`span_id_16hex=True` を確認。）
- [x] T-424 確認する: `x_amzn_trace_id` が補助情報として出力され、`trace_id` を上書きしていないことを確認する。（実施結果: Datadog Logs API で `x_amzn_trace_id` と `trace_id` が同時に存在し、両者が異なる値であることを確認。）
- [x] T-425 確認する: `traceId` / `spanId` の旧キーが JSON ログトップレベルに復活していないことを確認する。（実施結果: Datadog Logs API sample で `traceId_present=False`、`spanId_present=False` を確認。）
- [x] T-426 確認する: Trace から関連 Logs へ遷移できることを確認する。（実施結果: Datadog API で trace sample の `trace_id` を使って関連 Logs を検索できることを確認。ブラウザ UI のクリック操作は行っていないが、相関に必要な Datadog 側の同一 trace_id 取り込みは成立。）
- [x] T-427 確認する: Logs から該当 Trace へ遷移できることを確認する。（実施結果: Datadog Logs API で `trace_id` / `span_id` を持つ logs を取得し、同一 `trace_id` の spans も Datadog Spans API で取得できることを確認。ブラウザ UI のクリック操作は行っていない。）
- [x] T-428 判断する: Logs / APM の双方向遷移が成立しない場合のみ、Datadog Logs pipeline の Preprocessing / Trace ID remapper 設定タスクを追加する。（判断結果: Datadog API 上で logs / spans の同一 `trace_id` 相互検索が成立しているため、初期実装では pipeline 追加タスクを作らない。）
- [x] T-429 確認する: log fields に Authorization header、Cookie、JWT、DB 接続情報、PII 生値が含まれていないことを確認する。（実施結果: Datadog Logs API で Authorization / Cookie / JWT / password / secret / token 検索は 0件。既存実装どおり owner は hash のみ出力。）

## ドキュメント更新タスク

- [x] T-501 更新する: `backend/README.md` に Java Agent 導入後の O11y 主要環境変数、Maven artifact copy による Agent 同梱、`JAVA_TOOL_OPTIONS` attach、Trace / Span（業務/手動計装）方針を反映する。
- [x] T-502 更新する: `backend/README.md` の「Spring 管理 Bean の public メソッドを対象に span を付与する」前提を、限定的な業務 span 方針へ修正する。
- [x] T-503 更新する: `infra/README.md` に ECS task の app / Datadog Agent / FireLens 構成、Datadog Agent image pin、signal 別 OTLP 経路を反映する。
- [x] T-504 更新する: `infra/README.md` に `DD_APM_IGNORE_RESOURCES` による `/actuator/health` trace 除外方針を反映する。
- [x] T-505 更新する: `docs/infra/o11y.md` を Spring Boot OTel / Micrometer OTLP Registry 中心の記述から Java Agent 中心の Signal 別経路へ更新する。
- [x] T-506 更新する: `docs/backend/logging.md` に Java Agent Logback MDC または fallback 実装に合わせた `trace_id` / `span_id` 注入方式を反映する。
- [x] T-507 記載する: `docs/backend/logging.md` に Datadog Logs pipeline の remapper は初期実装では追加しない方針を記載する。
- [x] T-508 判断する: `docs/adr/adr-0003-OTel-DatadogAgent-settings.md` を更新するか supersede するかを決める。
- [x] T-509 更新する: ADR-0003 を更新または supersede する場合、Spring Boot OTel / Micrometer OTLP Registry 前提が Java Agent 導入後にどう変わるかを記録する。
- [x] T-510 判断する: `docs/adr/adr-0004-OTel-Java-Agent.md` の Datadog Agent OTLP ingest minimum version、関連 docs、owner / reviewers を更新するか決める。
- [x] T-511 更新する: ADR-0004 を更新する場合、Datadog Agent minimum version の差分と最終採用 version を記録する。
- [x] T-512 記録する: Datadog UI 上の metric 名や表示名は、検証で確認できたものだけを docs に記載する。
- [x] T-513 記録する: 未検証または環境依存の内容は docs の `確認事項` または `TODO` として明記する。

## 完了確認タスク

- [x] T-601 確認する: REQ-001 から REQ-013 の各要件に対応する実装または判断結果が存在することを確認する。
- [x] T-602 確認する: `specs.md` の backend / local 受け入れ条件を満たしていることを確認する。（実施結果: backend test / compile 成功、Docker build 成功、image 内 agent jar / 実行ユーザー読み取り / Java Agent attach を確認。実ログ相関は prod ECS + Datadog Logs API で確認。）
- [x] T-603 確認する: `specs.md` の Datadog APM 受け入れ条件を満たしていることを確認する。（実施結果: HTTP server span、JDBC client span、Trace / Span（業務/手動計装）span、同一 trace 関連、health trace 除外を Datadog Spans API で確認。）
- [x] T-604 確認する: `specs.md` の Datadog Metrics 受け入れ条件を満たしていることを確認する。（実施結果: JDBC / HikariCP / JVM runtime / CPU / business metrics と service/env/version tags を Datadog Metrics API で確認。）
- [x] T-605 確認する: `specs.md` の infra / ECS 受け入れ条件を満たしていることを確認する。（実施結果: CDK synth / diff / deploy 成功、ECS steady state、task definition env、Datadog Agent OTLP receiver、LogRouter error 0件、ALB target healthy を確認。）
- [x] T-606 確認する: `specs.md` の docs 受け入れ条件を満たしていることを確認する。
- [x] T-607 確認する: Datadog Java Tracer（`dd-java-agent.jar`）が導入されていないことを確認する。
- [x] T-608 確認する: OpenTelemetry Java Agent と Spring Boot OTel starter の SDK / exporter が二重運用されていないことを確認する。
- [x] T-609 確認する: Java Agent metrics exporter と Micrometer OTLP Registry が無条件に併用されていないことを確認する。
- [x] T-610 確認する: OTLP logs が有効化されておらず、`OTEL_LOGS_EXPORTER=none` が維持されていることを確認する。
- [x] T-611 確認する: `DD_SERVICE` / `DD_ENV` / `DD_VERSION` と `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES` の値が矛盾していないことを確認する。
- [x] T-612 確認する: `DD_VERSION` / `service.version` が `BackendImageDeploymentConstruct.imageTag` 由来であることを確認する。
- [x] T-613 確認する: API 契約、DB schema、Flyway migration、JPA entity、Cognito / JWT / owner_subject 境界に不要な変更が混ざっていないことを確認する。
- [x] T-614 確認する: frontend / load-test の機能変更が差分に混ざっていないことを確認する。
- [x] T-615 確認する: シークレット、`.env` の実値、Datadog API Key、DB 接続情報、秘密鍵、state file が差分に含まれていないことを確認する。
- [x] T-616 確認する: Java Agent debug logging が本番で常時有効になっていないことを確認する。
- [x] T-617 確認する: 未実施の検証がある場合、理由、影響、後続確認方法を docs または実装 PR メモに記録する。
- [x] T-618 確認する: 未解決事項のうち実装前に必須のものがすべて解決済みであることを確認する。
- [x] T-619 記録する: 実装後に残る未解決事項を `確認事項` または `TODO` として関連 docs に記録する。
- [x] T-620 確認する: `git diff` で変更範囲が `backend/`、`infra/`、`docs/`、対象 spec docs に限定されていることを確認する。
- [x] T-621 確認する: `git diff --check` で whitespace エラーがないことを確認する。

## 並列実施可能なタスク

- [x] T-701 並列実施する: T-014 から T-020 の前提決定後、backend image 変更（T-101 から T-107）と infra 設定変更（T-201 から T-240）は担当を分けて並列に進められる。
- [x] T-702 並列実施する: backend の手動 span / 業務 metrics 整理（T-141 から T-166）と Datadog Agent image pin / health 除外設定（T-231 から T-237）は、依存が薄いため並列に進められる。
- [x] T-703 並列実施する: 実装内容が確定した後、README / docs 更新（T-501 から T-507）と local / CDK 検証（T-301 から T-350）は並列に進められる。
- [x] T-704 並列実施する: Datadog APM、Metrics、Logs の受け入れ確認（T-381 から T-429）は、canary deploy 後に担当を分けて並列に進められる。（実施結果: 今回は単独実施だが、APM / Metrics / Logs は Datadog API で独立に確認できるため、次回以降は担当分担して並列実施可能。）

## 後続タスクとして分離する候補

- [x] T-801 分離する: Datadog DBM、Continuous Profiler、App and API Protection が必要になった場合は ADR-0004 再評価を別 feature として起票する。（判断結果: 今回の Java Agent 導入 smoke / canary では必要性が確認されていないため、本 feature では起票しない。必要になった時点で別 feature / Issue として扱う。）
- [x] T-802 分離する: API SLO、latency、throughput、error rate の dashboard / monitor / index / exclusion filter 詳細設計は、必要に応じて別 feature として起票する。（判断結果: Datadog API で見える範囲では `/actuator/health` 参照の dashboard / monitor は 0件。本 feature では詳細設計を追加せず、必要になった時点で別 feature / Issue とする。）
- [x] T-803 分離する: Datadog Logs pipeline の Trace ID remapper / Preprocessing は、初期実装で双方向相関が成立しない場合のみ別途実装タスクとして起票する。（判断結果: Datadog API 上で logs / spans の同一 `trace_id` 相互検索が成立しているため、本 feature では pipeline 追加タスクを起票しない。）
- [x] T-804 分離する: Fargate task size、Datadog Agent resource、`DD_APM_MAX_TPS` の本格的な負荷試験ベース調整は、canary 結果に基づき別途起票する。（判断結果: smoke / canary では ECS steady state、OOM / restart なし、代表 trace / metrics 取得可能。本格負荷試験で resource 不足や trace 欠落が出た場合に別 feature / Issue として扱う。）
