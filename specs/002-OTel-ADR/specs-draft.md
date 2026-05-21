# specs-draft.md: ECS Fargate 上の Spring Boot アプリに対する Datadog / OpenTelemetry オブザーバビリティ実装

- Status: Draft
- Date: 2026-05-21
- Related ADR: `adr/0001-observability-datadog-opentelemetry-ecs-fargate.md`
- Target state: 最終構成

## 1. 概要

本仕様は、AWS ECS Fargate 上で稼働する Spring Boot アプリケーションに対し、Datadog を主要なオブザーバビリティ基盤として導入するための最終構成を定義する。

対象アプリケーションは、現在 `SLF4J + Logback` によりログを出力している。本仕様では、このログ実装を置き換えず、アプリケーションログは引き続き `SLF4J + Logback` で出力する。

一方で、障害調査、性能調査、業務処理の追跡、メトリクス監視を Datadog 上で横断的に行えるようにするため、以下を実装する。

- Spring Boot アプリケーションへの OpenTelemetry Spring Boot Starter 導入
- OpenTelemetry API / annotations による重要業務処理の手動計装
- Datadog Agent sidecar による OTLP traces / metrics 受信
- FireLens / Fluent Bit によるアプリケーションログの Datadog Logs 転送
- Datadog 上での logs / traces / metrics 相関
- コスト制御、セキュリティ制御、運用監視

本仕様は最終構成を定義する。移行段階の暫定構成は対象外とする。

## 2. ゴール

## 2.1 機能ゴール

- アプリケーションログを Datadog Logs で検索できる。
- アプリケーションログに `trace_id` / `span_id` が含まれ、Datadog APM の trace と相関できる。
- HTTP リクエスト、DB アクセス、外部 HTTP 呼び出しなどの自動計装が Datadog APM で確認できる。
- 業務的に重要な処理が OpenTelemetry span として Datadog APM で確認できる。
- 業務的に重要な処理結果や処理時間が metrics として Datadog Metrics で確認できる。
- Datadog 上で `env` / `service` / `version` により logs / traces / metrics を横断できる。
- ECS Fargate タスク内の Datadog Agent sidecar と FireLens / Fluent Bit が正常稼働していることを監視できる。

## 2.2 非機能ゴール

- 既存の SLF4J ログ実装を維持し、業務コードへの影響を抑える。
- 手動 span の実装によるコード肥大化を避ける。
- ログ、span、metrics に PII や secrets を出力しない。
- Datadog Logs / APM / Metrics のコスト増加を抑制できる構成にする。
- CloudWatch Logs と Datadog Logs の二重全量長期保持を避ける。
- ECS Fargate タスクの CPU / memory に対して、Datadog Agent と FireLens のリソースを明示的に見積もる。

## 3. 非ゴール

以下は本仕様の対象外である。

- SLF4J / Logback を OpenTelemetry Logs API に置き換えること
- FireLens / Fluent Bit を traces / metrics の転送基盤として使用すること
- Datadog Agent sidecar を使わず、FireLens だけで Datadog APM / Metrics を実現すること
- すべてのメソッドに span を付与すること
- すべてのログを Datadog に長期インデックス保持すること
- 監査ログの長期保管基盤をアプリケーションログだけで実現すること
- PII / secrets を出力して後段でマスキングすることを前提にすること

## 4. 最終アーキテクチャ

```text
[ECS Fargate Task]

  [Spring Boot App Container]
    - SLF4J + Logback
    - JSON logs to stdout
    - OpenTelemetry Spring Boot Starter
    - @WithSpan / OpenTelemetry API manual instrumentation
    - OTLP traces / metrics exporter

       stdout / stderr logs
             |
             v
  [FireLens / Fluent Bit Log Router]
    - awsfirelens
    - Datadog Fluent Bit output
             |
             v
        Datadog Logs

       OTLP traces / metrics
             |
             v
  [Datadog Agent Sidecar]
    - ECS_FARGATE=true
    - APM enabled
    - OTLP receiver enabled
             |
             v
        Datadog APM / Metrics
```

CloudWatch Logs は、最終構成ではアプリケーションログの主要な閲覧先ではない。必要な場合のみ、FireLens / Datadog Agent 自体の診断ログや AWS 運用上の保険として短期保持する。

## 5. 要件

## 5.1 ログ要件

### REQ-LOG-001: アプリケーションログ API

アプリケーションコードでは、ログ出力 API として `SLF4J` を使用する。

許可する例:

```java
private static final Logger log = LoggerFactory.getLogger(OrderService.class);
```

または Lombok を使用する場合:

```java
@Slf4j
@Service
public class OrderService {
}
```

禁止する例:

```java
System.out.println("...");
```

```java
import ch.qos.logback.classic.Logger;
```

### REQ-LOG-002: ログ出力形式

本番環境のアプリケーションログは JSON 形式で stdout に出力する。

必須フィールド:

- `timestamp`
- `level`
- `logger`
- `thread`
- `message`
- `service`
- `env`
- `version`
- `trace_id`
- `span_id`

推奨フィールド:

- `request_id`
- `correlation_id`
- `error.type`
- `error.message`
- `error.stack`

### REQ-LOG-003: trace-log correlation

OpenTelemetry の trace context をログへ注入し、Datadog Logs で `trace_id` / `span_id` を top-level field として検索できるようにする。

受入条件:

- Datadog Logs のログイベントに `trace_id` が存在する。
- Datadog Logs のログイベントに `span_id` が存在する。
- Datadog APM の trace 画面から、該当処理中に出力されたログを確認できる。

### REQ-LOG-004: 出力禁止情報

次の情報をログに出力してはならない。

- password
- access token
- refresh token
- API key
- secret key
- Authorization header 全文
- Cookie 全文
- request body 全文
- response body 全文
- クレジットカード番号
- 銀行口座番号
- 個人番号
- 出力可否が未判断の PII

## 5.2 traces 要件

### REQ-TRACE-001: OpenTelemetry Spring Boot Starter

Spring Boot アプリケーションに OpenTelemetry Spring Boot Starter を導入する。

Maven 例:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.opentelemetry.instrumentation</groupId>
            <artifactId>opentelemetry-instrumentation-bom</artifactId>
            <version>${opentelemetry.instrumentation.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.opentelemetry.instrumentation</groupId>
        <artifactId>opentelemetry-spring-boot-starter</artifactId>
    </dependency>

    <dependency>
        <groupId>io.opentelemetry.instrumentation</groupId>
        <artifactId>opentelemetry-instrumentation-annotations</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aop</artifactId>
    </dependency>
</dependencies>
```

バージョンは BOM で管理し、固定値をコード上に散在させない。

### REQ-TRACE-002: 手動 span の対象

手動 span は、業務上意味のある処理境界に限定する。

対象例:

- 注文作成
- 決済承認
- 契約承認
- 顧客情報連携
- バッチ 1 レコード処理
- 外部システム連携の業務単位

対象外例:

- getter / setter
- 単純な private メソッド
- 1〜2 行の変換処理
- ログで十分な分岐
- HTTP / DB / 外部 HTTP 呼び出しの自動計装で十分な箇所

### REQ-TRACE-003: `@WithSpan` 優先

手動 span は、原則として `@WithSpan` を優先する。

例:

```java
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    @WithSpan("order.create")
    public void createOrder(
            @SpanAttribute("business.operation") String operation,
            @SpanAttribute("order.type") String orderType,
            @SpanAttribute("payment.method") String paymentMethod,
            CreateOrderRequest request
    ) {
        // business logic
    }
}
```

注意:

- `@WithSpan` は Spring AOP proxy ベースのため、同一クラス内の自己呼び出しでは期待どおり span が作られない場合がある。
- `@WithSpan` を付与するメソッドは、原則として Spring Bean の public method とする。

### REQ-TRACE-004: 直接 Tracer API の利用制限

`Tracer` / `Span` API を直接使用する場合は、次のいずれかに限定する。

- `@WithSpan` では表現できない非同期処理
- span attributes / events / status を細かく制御する必要がある処理
- 共通ヘルパーで span 作成・終了・例外記録を隠蔽できる処理

業務メソッド内に、次のような boilerplate を多数記述しない。

```java
Span span = tracer.spanBuilder("...").startSpan();
try (Scope scope = span.makeCurrent()) {
    // business logic
} catch (Exception e) {
    span.recordException(e);
    throw e;
} finally {
    span.end();
}
```

直接 API を使う場合の推奨例:

```java
@Component
public class TelemetrySupport {
    private final Tracer tracer;

    public TelemetrySupport(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("application");
    }

    public <T> T inSpan(String spanName, Supplier<T> action) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            return action.get();
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

### REQ-TRACE-005: span naming

span 名は、業務的な意味がわかる名前にする。

推奨:

- `order.create`
- `payment.authorize`
- `contract.approve`
- `customer.sync`
- `batch.importCustomerRecord`

非推奨:

- `execute`
- `process`
- `doSomething`
- `method1`

### REQ-TRACE-006: span attributes

span attributes は、検索・絞り込みに使う低カーディナリティ値に限定する。

推奨:

- `business.operation`
- `order.type`
- `payment.method`
- `external.system`
- `result.status`
- `error.category`

原則禁止:

- `customer.email`
- `customer.phone`
- `access.token`
- `request.body`
- `order.id` を metrics attributes として使うこと
- `customer.id` を metrics attributes として使うこと

`order.id` や `request.id` は、トラブルシューティング上必要な場合に限り、ログまたは trace attributes での扱いをセキュリティレビュー対象とする。

### REQ-TRACE-007: span events

span events は、span 内の重要な一時点に意味がある場合のみ記録する。

推奨例:

```java
Span.current().addEvent("payment.authorization.requested");
Span.current().addEvent("payment.authorization.approved");
```

非推奨例:

```java
Span.current().addEvent("started");
Span.current().addEvent("completed");
```

`started` / `completed` は通常 span 自体の開始・終了時刻で表現できるため、原則不要とする。

## 5.3 metrics 要件

### REQ-METRIC-001: 業務 metrics

業務上重要な数値は OpenTelemetry metrics として出力する。

例:

- `business.order.created.count`
- `business.order.failed.count`
- `business.order.creation.duration`
- `business.payment.authorization.failed.count`
- `business.external_api.retry.count`

### REQ-METRIC-002: metrics attributes

metrics attributes は低カーディナリティ値のみ使用する。

許可例:

- `env`
- `service`
- `operation`
- `result`
- `payment.method`
- `order.type`
- `external.system`

禁止例:

- `order.id`
- `customer.id`
- `request.id`
- `email`
- `phone`
- `session.id`

### REQ-METRIC-003: metrics 実装例

```java
@Service
public class OrderMetrics {
    private final LongCounter orderCreatedCounter;
    private final LongCounter orderFailedCounter;
    private final DoubleHistogram orderCreationDuration;

    public OrderMetrics(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.getMeter("application.business");

        this.orderCreatedCounter = meter
                .counterBuilder("business.order.created.count")
                .setDescription("Number of successfully created orders")
                .setUnit("{order}")
                .build();

        this.orderFailedCounter = meter
                .counterBuilder("business.order.failed.count")
                .setDescription("Number of failed order creations")
                .setUnit("{order}")
                .build();

        this.orderCreationDuration = meter
                .histogramBuilder("business.order.creation.duration")
                .setDescription("Order creation duration")
                .setUnit("ms")
                .build();
    }

    public void recordCreated(String orderType, String paymentMethod) {
        orderCreatedCounter.add(1, Attributes.of(
                AttributeKey.stringKey("order.type"), orderType,
                AttributeKey.stringKey("payment.method"), paymentMethod
        ));
    }
}
```

## 5.4 Datadog Agent sidecar 要件

### REQ-DDAGENT-001: Datadog Agent コンテナ

ECS Fargate タスク定義に Datadog Agent コンテナを追加する。

必須環境変数:

```text
ECS_FARGATE=true
DD_APM_ENABLED=true
DD_SITE=<datadog-site>
DD_ENV=<env>
DD_SERVICE=<service>
DD_VERSION=<version>
```

`DD_API_KEY` は Secrets Manager 等から secret として注入する。

### REQ-DDAGENT-002: OTLP receiver

OTLP/HTTP を採用する場合、Datadog Agent に次を設定する。

```text
DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318
```

アプリケーション側には次を設定する。

```text
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=otlp
OTEL_LOGS_EXPORTER=none
```

OTLP/gRPC を採用する場合、Datadog Agent に次を設定する。

```text
DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT=0.0.0.0:4317
```

アプリケーション側には次を設定する。

```text
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
OTEL_EXPORTER_OTLP_PROTOCOL=grpc
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=otlp
OTEL_LOGS_EXPORTER=none
```

本仕様では、簡潔性と HTTP ベースの確認容易性を優先し、OTLP/HTTP を第一候補とする。

### REQ-DDAGENT-003: OTLP logs ingestion

Datadog Agent の OTLP logs ingestion は、原則として有効化しない。

禁止する設定:

```text
DD_OTLP_CONFIG_LOGS_ENABLED=true
```

有効化する場合は、別 ADR で理由、対象ログ、想定ログ量、課金影響、保持期間、除外条件を定義する。

## 5.5 FireLens / Fluent Bit 要件

### REQ-FIRELENS-001: FireLens log router

ECS Fargate タスク定義に FireLens / Fluent Bit log router コンテナを追加する。

例:

```json
{
  "name": "log_router",
  "image": "public.ecr.aws/aws-observability/aws-for-fluent-bit:stable",
  "essential": true,
  "firelensConfiguration": {
    "type": "fluentbit",
    "options": {
      "enable-ecs-log-metadata": "true"
    }
  }
}
```

### REQ-FIRELENS-002: application container log driver

アプリケーションコンテナの log driver は `awsfirelens` とする。

例:

```json
{
  "logConfiguration": {
    "logDriver": "awsfirelens",
    "options": {
      "Name": "datadog",
      "Host": "http-intake.logs.datadoghq.com",
      "TLS": "on",
      "provider": "ecs",
      "dd_service": "<service>",
      "dd_source": "java",
      "dd_tags": "env:<env>,service:<service>,version:<version>",
      "dd_message_key": "message",
      "compress": "gzip"
    },
    "secretOptions": [
      {
        "name": "apikey",
        "valueFrom": "<datadog-api-key-secret-arn>"
      }
    ]
  }
}
```

Datadog site が `datadoghq.com` 以外の場合は、`Host` を対象 site に合わせる。

### REQ-FIRELENS-003: CloudWatch Logs の扱い

最終構成では、アプリケーションコンテナのログを `awslogs` で CloudWatch Logs に送ることを標準としない。

CloudWatch Logs を使う場合は、用途を以下のいずれかに限定する。

- FireLens log router の診断ログ
- Datadog Agent の診断ログ
- AWS 運用上の短期保険
- 監査上の要件が明確なログ

保持期間は明示する。

## 5.6 Datadog tagging 要件

### REQ-TAG-001: Unified Service Tagging

以下のタグを統一する。

- `env`
- `service`
- `version`

アプリケーション環境変数例:

```text
DD_ENV=prod
DD_SERVICE=<service>
DD_VERSION=<git-sha-or-release-version>
OTEL_SERVICE_NAME=<service>
OTEL_RESOURCE_ATTRIBUTES=service.name=<service>,service.version=<version>,deployment.environment=prod
```

### REQ-TAG-002: Datadog 上の確認

Datadog 上で、logs / traces / metrics が同じ `env` / `service` / `version` で検索できることを確認する。

## 6. アプリケーション設定例

## 6.1 application.yml 例

```yaml
otel:
  service:
    name: ${OTEL_SERVICE_NAME:${DD_SERVICE:unknown-service}}
  resource:
    attributes:
      service.name: ${OTEL_SERVICE_NAME:${DD_SERVICE:unknown-service}}
      service.version: ${DD_VERSION:unknown-version}
      deployment.environment: ${DD_ENV:local}

logging:
  level:
    root: INFO
    com.example: INFO
```

環境変数による設定を優先し、環境差分をアプリケーション jar に埋め込まない。

## 6.2 logback-spring.xml 例

実際の encoder はプロジェクト標準に合わせる。以下は JSON ログの概念例である。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="service" source="DD_SERVICE" defaultValue="unknown-service"/>
    <springProperty scope="context" name="env" source="DD_ENV" defaultValue="local"/>
    <springProperty scope="context" name="version" source="DD_VERSION" defaultValue="unknown-version"/>

    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${service}","env":"${env}","version":"${version}"}</customFields>
            <includeMdcKeyName>trace_id</includeMdcKeyName>
            <includeMdcKeyName>span_id</includeMdcKeyName>
            <includeMdcKeyName>request_id</includeMdcKeyName>
            <includeMdcKeyName>correlation_id</includeMdcKeyName>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

受入テストでは、実際のログに `trace_id` と `span_id` が入ることを必ず確認する。

## 7. 実装例

## 7.1 Service span の例

```java
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderService {

    private final OrderMetrics orderMetrics;

    public OrderService(OrderMetrics orderMetrics) {
        this.orderMetrics = orderMetrics;
    }

    @WithSpan("order.create")
    public void createOrder(
            @SpanAttribute("business.operation") String operation,
            @SpanAttribute("order.type") String orderType,
            @SpanAttribute("payment.method") String paymentMethod,
            CreateOrderRequest request
    ) {
        long startNanos = System.nanoTime();

        try {
            log.info("Order creation started. orderType={}, paymentMethod={}", orderType, paymentMethod);

            reserveStock(request);

            Span.current().addEvent("payment.authorization.requested");
            authorizePayment(request);
            Span.current().addEvent("payment.authorization.approved");

            saveOrder(request);

            Span.current().setAttribute("result.status", "success");
            orderMetrics.recordCreated(orderType, paymentMethod);

            log.info("Order created. orderType={}, paymentMethod={}", orderType, paymentMethod);

        } catch (RuntimeException e) {
            Span.current().setAttribute("result.status", "failure");
            Span.current().recordException(e);
            orderMetrics.recordFailed(orderType, paymentMethod);
            log.error("Order creation failed. orderType={}, paymentMethod={}", orderType, paymentMethod, e);
            throw e;

        } finally {
            double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            orderMetrics.recordDuration(orderType, paymentMethod, durationMs);
        }
    }

    private void reserveStock(CreateOrderRequest request) {
        // 在庫引当
    }

    private void authorizePayment(CreateOrderRequest request) {
        // 決済承認
    }

    private void saveOrder(CreateOrderRequest request) {
        // DB 保存
    }
}
```

注意:

- `orderId` や `customerId` を metrics attributes に入れない。
- `request` 全体をログや span attributes に出力しない。
- `started` / `completed` だけの span events は原則追加しない。

## 8. Datadog 設定要件

## 8.1 Logs

Datadog Logs で以下を設定する。

- `service` / `env` / `version` の確認
- JSON parsing の確認
- `trace_id` / `span_id` の確認
- Index の設計
- Exclusion Filter の設計
- retention の設計
- daily quota の設計
- ERROR / WARN ログの monitor
- ログ量の monitor

## 8.2 APM

Datadog APM で以下を確認する。

- service が表示されること
- HTTP request trace が表示されること
- DB / external HTTP spans が表示されること
- `@WithSpan` による業務 span が表示されること
- span attributes が検索可能であること
- エラー時に span error と exception が確認できること

## 8.3 Metrics

Datadog Metrics で以下を確認する。

- 業務 metrics が表示されること
- attributes が想定どおり tag 化されていること
- 高カーディナリティ tag が発生していないこと
- dashboard で成功件数 / 失敗件数 / 処理時間を確認できること
- monitor で異常検知できること

## 9. 受入条件

## 9.1 ログ

- [ ] アプリログが stdout に JSON で出力される。
- [ ] アプリログが FireLens / Fluent Bit 経由で Datadog Logs に到達する。
- [ ] Datadog Logs で `service` / `env` / `version` による検索ができる。
- [ ] Datadog Logs で `trace_id` / `span_id` が確認できる。
- [ ] Datadog APM trace から該当ログへ遷移できる。
- [ ] 本番ログに DEBUG が常時出力されていない。
- [ ] 出力禁止情報がログに含まれていない。

## 9.2 traces

- [ ] OpenTelemetry Spring Boot Starter が導入されている。
- [ ] Datadog Agent sidecar の OTLP receiver が有効である。
- [ ] アプリケーションから Datadog Agent に traces が送信される。
- [ ] HTTP / DB / 外部 HTTP の自動計装 span が確認できる。
- [ ] 業務 span が Datadog APM で確認できる。
- [ ] span attributes が設計どおり付与されている。
- [ ] エラー時に span が error として確認できる。

## 9.3 metrics

- [ ] 業務 metrics が Datadog Metrics に到達する。
- [ ] metrics attributes に高カーディナリティ値が含まれていない。
- [ ] 業務成功件数、失敗件数、処理時間が dashboard で確認できる。
- [ ] 必要な monitor が設定されている。

## 9.4 ECS / sidecar

- [ ] ECS Fargate タスク定義に app / datadog-agent / log_router が含まれる。
- [ ] Datadog Agent container が正常起動する。
- [ ] FireLens / Fluent Bit container が正常起動する。
- [ ] app container の log driver が `awsfirelens` である。
- [ ] Datadog API key が secretOptions / secrets で渡され、平文で定義されていない。
- [ ] sidecar の CPU / memory がタスク定義で見積もられている。

## 9.5 コスト

- [ ] Datadog Logs Index / Exclusion Filter / retention / daily quota が定義されている。
- [ ] CloudWatch Logs の用途と保持期間が明示されている。
- [ ] Datadog logs ingestion / indexed logs の使用量 monitor がある。
- [ ] custom metrics / APM ingestion の使用量 monitor がある。

## 10. テスト方針

## 10.1 ローカル / 開発環境

- OpenTelemetry exporter endpoint をローカル Collector または Datadog Agent に向ける。
- サンプルリクエストで trace が作成されることを確認する。
- JSON ログに `trace_id` / `span_id` が含まれることを確認する。
- 出力禁止情報がログに出ていないことを確認する。

## 10.2 結合環境

- ECS Fargate 上で app / datadog-agent / log_router が同一タスクとして起動することを確認する。
- FireLens 経由で Datadog Logs にログが届くことを確認する。
- OTLP 経由で Datadog APM / Metrics に telemetry が届くことを確認する。
- Datadog APM trace と Datadog Logs が相関できることを確認する。

## 10.3 障害試験

以下を意図的に発生させ、Datadog 上で確認できることを検証する。

- 業務処理失敗
- 外部 API 失敗
- DB エラー
- タイムアウト
- FireLens 転送失敗
- Datadog Agent 起動失敗
- OTLP endpoint 接続失敗

## 11. 運用要件

## 11.1 ダッシュボード

最低限、以下の Datadog dashboard を用意する。

- Service Overview
- APM Latency / Throughput / Error
- Business Operation Metrics
- ECS Fargate Task Health
- Datadog Logs Usage
- Datadog APM / Metrics Usage

## 11.2 Monitor

最低限、以下の Datadog monitor を用意する。

- error rate increase
- latency p95 / p99 threshold
- business failure count threshold
- external API failure count threshold
- Datadog Agent unhealthy
- FireLens / Fluent Bit error
- logs ingestion anomaly
- indexed logs anomaly
- custom metrics cardinality anomaly

## 12. 未決事項

以下はプロジェクトで確定する必要がある。

- Datadog site: `datadoghq.com` / `ap1.datadoghq.com` / その他
- service 名の正式値
- env 名の正式値: `dev` / `stg` / `prod` 等
- version の値: Git SHA、SemVer、CI build number のどれを採用するか
- CloudWatch Logs を残す場合の保持期間
- Datadog Logs の retention
- Datadog Index / Exclusion Filter の具体条件
- APM sampling rate
- 本番で許容するログ量の目標値
- sidecar 用 CPU / memory の標準値
- 監査ログの別保管要件

## 13. 参考資料

公式資料を中心に、2026-05-21 時点で確認した。

- Spring Boot Logging: https://docs.spring.io/spring-boot/reference/features/logging.html
- OpenTelemetry Spring Boot Starter: https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/
- OpenTelemetry Spring Boot Starter - Extending instrumentations with the API: https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/api/
- OpenTelemetry Spring Boot Starter - Annotations: https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/annotations/
- OpenTelemetry Spring Boot Starter - Out of the box instrumentation: https://opentelemetry.io/ja/docs/zero-code/java/spring-boot-starter/out-of-the-box-instrumentation/
- OpenTelemetry Logs API specification: https://opentelemetry.io/docs/specs/otel/logs/api/
- Datadog ECS Fargate integration: https://docs.datadoghq.com/ja/integrations/ecs_fargate/
- Datadog OTLP Ingestion by the Agent: https://docs.datadoghq.com/opentelemetry/setup/otlp_ingest_in_the_agent/
- Datadog Correlate OpenTelemetry Traces and Logs: https://docs.datadoghq.com/opentelemetry/correlate/logs_and_traces/
- Datadog Unified Service Tagging: https://docs.datadoghq.com/getting_started/tagging/unified_service_tagging/
- Datadog Log Indexes: https://docs.datadoghq.com/ja/logs/log_configuration/indexes/
- AWS ECS FireLens / AWS for Fluent Bit: https://docs.aws.amazon.com/ja_jp/AmazonECS/latest/developerguide/firelens-using-fluentbit.html
