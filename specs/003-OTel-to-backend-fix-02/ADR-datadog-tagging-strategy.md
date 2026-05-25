# ADR-001: Datadog オブザーバビリティ設定戦略

## ステータス

承認済み

## コンテキスト

本プロジェクトは以下の構成で AWS ECS (Fargate) 上の Java アプリケーションを Datadog で監視する。

- **Spring Boot 4.0.5 + Maven** によるアプリケーションビルド
- **`spring-boot-starter-opentelemetry 4.0.5`** 導入済み（OTel API・OTLP エクスポートを内包）
- **OpenTelemetry API / `@WithSpan` アノテーション** による重要業務処理の手動計装
- **Datadog Agent サイドカー** による OTLP traces / metrics 受信 (gRPC:4317)

同一の Datadog Organization 内に複数の AWS アカウント・複数システムの監視データが混在するため、
テレメトリ（メトリクス・トレース・ログ）を一意に識別・分離できるタグ設計が必要である。

加えて、アプリログとトレースを Datadog UI 上で相関させる（Log Correlation）ための設定も必要である。

---

## テレメトリ種別とタグの関係

アプリが送信するテレメトリは以下の 3 種別に分類される。
APM が記録する DB 呼び出しは**ログではなくトレースのスパン**として記録される点に注意する。

```
ログ（Logs）       → アプリが stdout/stderr に出力するテキスト
トレース（Traces） → OTel Spring Boot Starter が計装したリクエスト処理経路
  └─ スパン        → 個々の処理単位（HTTP・DB・@WithSpan による業務処理等）
メトリクス         → JVM・HTTP サーバー等の数値時系列データ
```

---

## 決定

### 1. 環境変数の設定方針

#### OTel Spring Boot Starter が読む変数（アプリコンテナ）

`spring-boot-starter-opentelemetry` は `DD_*` 変数を直接読まない。
OTel 標準の環境変数で設定し、Datadog Agent が受信時に自動マッピングする。

| 環境変数 | 設定値の例 | Datadog タグへのマッピング |
|---|---|---|
| `OTEL_SERVICE_NAME` | `todo-backend` | `service:todo-backend` |
| `OTEL_RESOURCE_ATTRIBUTES` | `deployment.environment=prod,service.version=7bf7d506` | `env:prod`, `version:7bf7d506` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | （送信先指定） |

#### Datadog Agent サイドカーが読む変数

Datadog の予約済み 3 タグ（Unified Service Tagging）とカスタムタグを設定する。

| 環境変数 | タグキー | 設定値の例 | 説明 |
|---|---|---|---|
| `DD_SERVICE` | `service` | `todo-backend` | APM サービスマップ・ログの service タグに使用される |
| `DD_ENV` | `env` | `prod` | Datadog 画面上部の env 切り替えに連動する |
| `DD_VERSION` | `version` | `7bf7d506...` | デプロイ追跡・バージョン別エラー率比較に使用される。デプロイごとに更新する |
| `DD_TAGS` | （複数） | 下記参照 | カスタムタグ。受信した全テレメトリに付与される |

`DD_TAGS` に含めるカスタムタグ:

| タグキー | 設定値の例 | 説明 |
|---|---|---|
| `team` | `platform` | 担当チーム。コスト配賦・アラートルーティングに活用 |
| `aws_account` | `123456789012` | AWS アカウント ID。複数アカウントの監視データを分離するために使用 |
| `system` | `todo` | 複数サービスをまとめる論理グループ。将来的なマイクロサービス化に備えて付与する |

### 2. タグの重複に関するルール

- `DD_SERVICE` / `DD_ENV` / `DD_VERSION` は専用変数で設定し、`DD_TAGS` には記載しない（重複を避けるため）
- `OTEL_SERVICE_NAME` と `DD_SERVICE` は同じ値を設定する。Datadog Agent が受信時に OTel 属性を Datadog タグに自動マッピングするが、競合時は `DD_*` が優先される

### 3. テレメトリ種別ごとのタグ付与の挙動

| テレメトリ | タグ自動付与 | 必要な追加設定 |
|---|---|---|
| **トレース・スパン**（DB 呼び出し・HTTP・`@WithSpan` 等） | ✅ OTel 変数から自動付与 | 不要 |
| **ログの `service` / `env`** | ✅ Datadog Agent が ECS メタデータから自動付与 | 不要 |
| **ログの `trace_id`（Log Correlation）** | ❌ 自動では挿入されない | 後述の Maven 依存・Bean・logback 設定が必要 |
| **メトリクス**（JVM・HTTP 等） | ✅ OTel 変数から自動付与 | 不要 |

### 4. Log Correlation の実装要件

`spring-boot-starter-opentelemetry` を導入しただけでは、ログへの `trace_id` / `span_id` 自動挿入は行われない。
以下の 3 点を必ず実施すること。

#### (1) pom.xml への依存追加

`spring-boot-starter-opentelemetry` に Logback Appender は含まれていないため、別途追加する。
`-alpha` サフィックスは OTel プロジェクト側の安定版未リリースを意味するものであり、実用上の問題はない。

```xml
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
    <version>2.21.0-alpha</version>
</dependency>
```

#### (2) OpenTelemetryAppender を Spring Bean として初期化

Spring Boot 4 では、Logback Appender に対して OTel インスタンスを明示的に渡す初期化 Bean が必要である。
この Bean がないと、Appender が OTel SDK と接続されずログが転送されない。

```java
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
class InstallOpenTelemetryAppender implements InitializingBean {

    private final OpenTelemetry openTelemetry;

    InstallOpenTelemetryAppender(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(this.openTelemetry);
    }
}
```

#### (3) logback-spring.xml の設定

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <!-- Spring Boot デフォルトのログ設定（CONSOLE appender 等）を継承 -->
  <include resource="org/springframework/boot/logging/logback/base.xml"/>

  <!-- OTel Logback Appender: trace_id / span_id を自動挿入して OTLP 送信する -->
  <appender name="OTEL"
      class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
  </appender>

  <root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="OTEL"/>
  </root>
</configuration>
```

これにより、スパン実行中に出力されたログに `trace_id` / `span_id` が自動的に埋め込まれ、
Datadog UI 上でトレースとログを相互参照できるようになる。

### 5. CDK 実装例

```typescript
// アプリコンテナ
const appContainer = taskDefinition.addContainer('AppContainer', {
  image: ecs.ContainerImage.fromEcrRepository(repo, 'latest'),
  environment: {
    // OTel Spring Boot Starter が読む変数
    OTEL_SERVICE_NAME:        'todo-backend',
    OTEL_RESOURCE_ATTRIBUTES: `deployment.environment=prod,service.version=${process.env.IMAGE_TAG ?? 'unknown'}`,
    OTEL_EXPORTER_OTLP_ENDPOINT: 'http://localhost:4317',
  },
  logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'app' }),
});

// Datadog Agent サイドカー
const datadogContainer = taskDefinition.addContainer('DatadogAgent', {
  image: ecs.ContainerImage.fromRegistry('public.ecr.aws/datadog/agent:latest'),
  essential: false,
  environment: {
    DD_SITE:        'datadoghq.com',
    DD_APM_ENABLED: 'true',
    ECS_FARGATE:    'true',
    DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT: '0.0.0.0:4317',

    // Unified Service Tagging（予約済み 3 タグ）
    DD_SERVICE: 'todo-backend',
    DD_ENV:     'prod',
    DD_VERSION: process.env.IMAGE_TAG ?? 'unknown',

    // カスタムタグ
    DD_TAGS: [
      'team:platform',
      `aws_account:${Stack.of(this).account}`,
      'system:todo',
    ].join(' '),
  },
  secrets: {
    DD_API_KEY: ecs.Secret.fromSecretsManager(ddApiKeySecret, 'DD_API_KEY'),
  },
  portMappings: [{ containerPort: 4317 }],
  logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'datadog-agent' }),
});

// Secrets Manager 読み取り権限を付与
ddApiKeySecret.grantRead(taskDefinition.executionRole!);
```

---

## 理由

### Unified Service Tagging を使う理由

Datadog の Unified Service Tagging は `env` / `service` / `version` の 3 タグを使ってテレメトリを横断的に結びつける仕組みであり、これにより APM サービスマップ・デプロイ追跡・バージョン別エラー率比較などの機能が自動的に有効になる。

> 参照: [Datadog - Unified Service Tagging](https://docs.datadoghq.com/getting_started/tagging/unified_service_tagging/)

### OTel 変数と DD 変数を両方設定する理由

`spring-boot-starter-opentelemetry` は `OTEL_SERVICE_NAME` 等の OTel 標準変数を読む。
一方、Datadog Agent がログやメタデータに `service` / `env` タグを付与する際は `DD_SERVICE` 等を参照する。
両方設定することで、トレース・ログ・メトリクス全てのテレメトリで一貫したタグが付与される。

> 参照: [Datadog - Unified Service Tagging (OTel section)](https://docs.datadoghq.com/getting_started/tagging/unified_service_tagging/)

### DD_TAGS でカスタムタグを付与する理由

同一 Datadog Organization 内で複数の AWS アカウントが共存する場合、`DD_SERVICE` だけでは同名サービスが複数アカウントに存在した場合に区別できない。
`aws_account` タグを付与することで、ダッシュボード・アラートの絞り込みが AWS アカウント単位で行えるようになる。

> 参照: [Datadog - Assigning Tags](https://docs.datadoghq.com/getting_started/tagging/assigning_tags/)

### system タグを付与する理由

現時点では `todo-backend` は単一サービスだが、将来的にマイクロサービスが増加した際に `system:todo` で全サービスをまとめて参照できる。
タグは後から付与すると既存データに遡及適用されないため、設計当初から付与する。

> 参照: [Datadog - Unified Tagging Advanced Usage Guide](https://docs.datadoghq.com/extend/guide/unified-tagging-advanced-usage/)

### Log Correlation に opentelemetry-logback-appender を使う理由

`spring-boot-starter-opentelemetry` だけではログへの `trace_id` 自動挿入は行われない。
OpenTelemetry Logback Appender を追加することで、ログに `trace_id` / `span_id` が自動的に埋め込まれ、
トレースとログを Datadog UI 上で相互参照できるようになる。

Spring Boot 4 では Appender の自動設定は行われないため、`OpenTelemetryAppender.install()` を呼び出す
初期化 Bean の実装が必須である。

> 参照: [Datadog - Correlate OpenTelemetry Traces and Logs](https://docs.datadoghq.com/opentelemetry/correlate/logs_and_traces/)
> 参照: [OpenTelemetry - Spring Boot Starter Out-of-the-box instrumentation](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/out-of-the-box-instrumentation/)
> 参照: [opentelemetry-logback-appender-1.0 README](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/logback/logback-appender-1.0/library/README.md)

### API Key を Secrets Manager で管理する理由

ECS タスク定義に平文で API Key を含めると、タスク定義の参照権限を持つ IAM プリンシパル全員がキーを参照できる。
AWS Secrets Manager を使用することで、キーへのアクセスを IAM ポリシーで制御でき、CloudTrail による参照履歴の監査も可能になる。

> 参照: [AWS - Passing sensitive data to a container (ECS)](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/secrets-envvar-secrets-manager.html)

---

## 結果

- Datadog 画面上で `env` / `service` / `aws_account` / `system` による絞り込みが可能になる
- 複数 AWS アカウントの監視データが同一 Org に混在しても、タグで論理分離できる
- デプロイごとに `DD_VERSION` を更新することで、バージョン別のエラー率比較・デプロイ追跡が有効になる
- DB 呼び出し等の APM スパンに `service` / `env` / `version` タグが自動付与される
- `opentelemetry-logback-appender-1.0` の導入により、ログと APM トレースを `trace_id` で相互参照できる
- タグ設計を CDK コードで管理するため、環境間の設定ドリフトを防止できる

---

## 参照

| ドキュメント | URL |
|---|---|
| Unified Service Tagging（Datadog 公式） | https://docs.datadoghq.com/getting_started/tagging/unified_service_tagging/ |
| Assigning Tags（Datadog 公式） | https://docs.datadoghq.com/getting_started/tagging/assigning_tags/ |
| Unified Tagging Advanced Usage（Datadog 公式） | https://docs.datadoghq.com/extend/guide/unified-tagging-advanced-usage/ |
| Correlate OTel Traces and Logs（Datadog 公式） | https://docs.datadoghq.com/opentelemetry/correlate/logs_and_traces/ |
| Correlating Java Logs and Traces（Datadog 公式） | https://docs.datadoghq.com/tracing/other_telemetry/connect_logs_and_traces/java/ |
| Spring Boot Starter Out-of-the-box instrumentation（OTel 公式） | https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/out-of-the-box-instrumentation/ |
| opentelemetry-logback-appender-1.0 README（OTel GitHub） | https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/instrumentation/logback/logback-appender-1.0/library/README.md |
| ECS - Secrets Manager 連携（AWS 公式） | https://docs.aws.amazon.com/AmazonECS/latest/developerguide/secrets-envvar-secrets-manager.html |
