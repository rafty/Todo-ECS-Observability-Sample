# ADR-0001: ECS Fargate 上の Spring Boot アプリにおける Datadog / OpenTelemetry / ログ収集方式

- Status: Proposed（レビュー後に Accepted へ変更する）
- Date: 2026-05-21
- Decision owner: TBD
- Related specs: `specs-draft.md`
- Related plan: `plan.md`（作成予定）
- Related tasks: `tasks.md`（作成予定）

## 1. 背景

本プロジェクトは、AWS ECS Fargate 上で稼働する Spring Boot アプリケーションに対して、Datadog を用いたオブザーバビリティを実装するためのプロジェクトである。

現在のアプリケーションは、アプリケーションコードでは SLF4J を使ってログを出力し、Spring Boot 標準の Logback をログ実装として利用している。ログはコンテナ標準出力または標準エラーを経由して CloudWatch Logs で参照できる状態である。

Spring Boot では、Starter を利用する場合、デフォルトのログ実装として Logback が使用される。したがって、現在の `SLF4J + Logback` 構成は Spring Boot アプリケーションとして一般的であり、アプリケーションログを出力する仕組みとしては継続利用する。

一方で、CloudWatch Logs だけでは、次のような調査が難しい。

- 1 つのリクエストが複数の処理、DB、外部 API 呼び出しをどのように通過したかを時系列で把握すること
- 業務処理単位の処理時間、失敗箇所、遅延箇所を trace として確認すること
- logs、traces、metrics を同じ `service` / `env` / `version` で横断的に確認すること
- 業務的に重要な処理を span、span attributes、span events、metrics として表現すること

そのため、Datadog を主要なオブザーバビリティ基盤として導入し、OpenTelemetry API による手動計装を併用する。

## 2. 用語整理

### 2.1 SLF4J / Logback

アプリケーションが従来から使用しているログ出力の仕組みである。

- SLF4J: アプリケーションコードが利用するロギング API / facade
- Logback: Spring Boot の標準的なロギング実装
- 出力先: 原則として stdout / stderr
- 主な用途: 人が読むアプリケーションログ、業務ログ、エラーログ、障害調査ログ

### 2.2 OpenTelemetry traces / spans

処理の流れ、親子関係、処理時間、失敗箇所を表現するためのテレメトリである。

- trace: 1 つのリクエストまたは処理全体の流れ
- span: trace の中の 1 つの処理単位
- attributes: span に付与する検索・絞り込み用の属性
- events: span 内で発生した重要な時点の記録

### 2.3 OpenTelemetry metrics

数値として集計・監視するためのテレメトリである。

- 成功件数
- 失敗件数
- 処理時間
- リトライ回数
- キュー滞留数
- 業務処理の結果件数

### 2.4 OpenTelemetry logs

OpenTelemetry の Logs API は、既存の SLF4J / Logback / Log4j / JUL などを置き換えるためのものではなく、既存ログフレームワークのログを OpenTelemetry のログデータモデルへ橋渡しするための API である。

そのため、本プロジェクトでは通常のアプリケーションログを OpenTelemetry Logs API に直接置き換えない。通常ログは `SLF4J + Logback` のまま維持する。

### 2.5 Datadog Agent sidecar

ECS Fargate タスク内で、アプリケーションコンテナと同じタスク定義に配置する Datadog Agent コンテナである。

主な役割は次の通りである。

- Datadog APM / traces の受け口
- OpenTelemetry OTLP traces / metrics の受け口
- ECS Fargate のコンテナメトリクス収集
- Datadog へのテレメトリ転送

### 2.6 FireLens / Fluent Bit

ECS Fargate タスク内で、コンテナの stdout / stderr ログを Datadog Logs へ転送するためのログルーターである。

Datadog Agent sidecar と FireLens / Fluent Bit は代替関係ではない。役割が異なる。

```text
Datadog Agent sidecar
  -> traces / metrics / APM / OTLP の受け口

FireLens / Fluent Bit
  -> stdout / stderr のアプリケーションログを Datadog Logs へ送るログルーター
```

## 3. 決定

本プロジェクトでは、最終構成として以下を採用する。

## 3.1 Datadog を主要な調査画面とする

通常の障害調査、性能調査、業務処理の追跡、アラート確認、ダッシュボード確認は Datadog を主画面とする。

CloudWatch Logs は、最終構成においてアプリケーションログの主たる閲覧先とはしない。CloudWatch Logs を残す場合は、AWS 運用上の保険、FireLens / Agent 自体の診断ログ、監査上の要件、または短期保持の用途に限定する。

## 3.2 アプリケーションログは SLF4J + Logback を継続利用する

アプリケーションコードでは、引き続き `org.slf4j.Logger` または Lombok の `@Slf4j` を使用する。

```java
private static final Logger log = LoggerFactory.getLogger(OrderService.class);
```

ログ実装は Spring Boot 標準の Logback を継続利用する。OpenTelemetry Logs API を通常ログの主要 API として使用しない。

## 3.3 アプリケーションログは stdout に JSON 形式で出力する

Datadog Logs で検索、集計、trace 連携しやすくするため、アプリケーションログは標準出力に JSON 形式で出力する。

ログには少なくとも次の情報を含める。

- timestamp
- level
- logger
- thread
- message
- exception stack trace
- service
- env
- version
- trace_id
- span_id
- 必要に応じた request_id / correlation_id

`trace_id` と `span_id` は、OpenTelemetry の Logback MDC 計装、OpenTelemetry-aware appender、または同等の仕組みによりログへ注入する。

## 3.4 アプリケーションログは FireLens / Fluent Bit で Datadog Logs へ送信する

アプリケーションコンテナのログドライバーは、最終構成では `awsfirelens` を使用する。

```text
Spring Boot app
  -> stdout / stderr
  -> FireLens / Fluent Bit
  -> Datadog Logs
```

既存の `awslogs -> CloudWatch Logs` を Datadog の主たるログ経路とはしない。

ただし、FireLens ログルーター自身のログや Datadog Agent 自身の診断ログについては、運用要件に応じて CloudWatch Logs または Datadog Logs に送る。

## 3.5 OpenTelemetry traces / metrics は Datadog Agent sidecar に OTLP で送信する

Spring Boot アプリケーションには OpenTelemetry Spring Boot Starter を導入し、OpenTelemetry API を使った手動計装を行う。

OpenTelemetry SDK から出力される traces / metrics は、同じ ECS Fargate タスク内の Datadog Agent sidecar に OTLP で送信する。

```text
Spring Boot app
  -> OpenTelemetry traces / metrics
  -> OTLP HTTP or OTLP gRPC
  -> Datadog Agent sidecar
  -> Datadog APM / Metrics
```

Datadog Agent は OTLP traces / metrics の受信を有効化する。OTLP logs の受信は、予期しないログ課金を避けるため、原則として無効のままとする。

## 3.6 FireLens は traces / metrics の送信には使わない

FireLens / Fluent Bit はログ転送用であり、OpenTelemetry traces / metrics の受信・転送基盤としては使用しない。

traces / metrics は Datadog Agent sidecar に送る。

## 3.7 手動 span は重要な業務境界に限定する

OpenTelemetry API による手動計装は可能であるが、業務コードに `spanBuilder`、`try/catch/finally`、`recordException`、`span.end()` などを過剰にベタ書きすると、業務ロジックの可読性が大きく下がる。

そのため、手動 span の方針を次のように定める。

1. まず OpenTelemetry Spring Boot Starter の自動計装を利用する。
2. 業務的に重要な Service 境界に `@WithSpan` を付与する。
3. span attributes は検索・絞り込みに意味がある低カーディナリティの値に限定する。
4. span events は、span 内の重要な一時点に意味がある場合のみ記録する。
5. 直接 `Tracer` / `Span` API を使う場合は、共通ヘルパーまたは AOP に逃がし、業務ロジックを肥大化させない。
6. PII、秘密情報、アクセストークン、パスワード、個人を直接識別する情報は span attributes / events / logs / metrics に記録しない。

## 3.8 metrics は集計目的に限定し、高カーディナリティ属性を避ける

OpenTelemetry metrics は、Datadog 上で集計・アラート・ダッシュボード化する目的で使用する。

使用する metrics の例:

- 業務処理成功件数
- 業務処理失敗件数
- 業務処理時間のヒストグラム
- 外部 API 呼び出し失敗件数
- リトライ回数

metrics attributes には `orderId`、`customerId`、`requestId` などの高カーディナリティ値を使用しない。

## 3.9 Datadog Unified Service Tagging を標準化する

Datadog 上で logs / traces / metrics を横断できるよう、次のタグを必ず統一する。

- `env`
- `service`
- `version`

OpenTelemetry 側では、次の resource attributes を設定する。

- `service.name`
- `service.version`
- `deployment.environment`

Datadog 側の `env` / `service` / `version` と OpenTelemetry resource attributes の対応を明確にし、ログ、trace、metrics で同一のサービスとして扱われるようにする。

## 3.10 コスト制御を設計に含める

Datadog と CloudWatch の両方に全量ログを長期保存する構成は、調査性は高いがコストが二重化しやすい。

そのため、最終構成では次の方針を採用する。

- Datadog Logs を主要なログ検索・調査画面とする。
- CloudWatch Logs は、必要最小限の用途に限定する。
- アプリケーションログを Datadog Logs に送る前提で、不要な DEBUG / INFO ログを削減する。
- Datadog の Index / Exclusion Filter / retention / daily quota を設計対象に含める。
- 本番では DEBUG ログを常時出さない。
- ログ量、indexed logs、custom metrics、APM ingestion を運用メトリクスとして監視する。

## 4. 採用する最終アーキテクチャ

```text
[ECS Fargate Task]

  [Spring Boot App Container]
    - SLF4J + Logback
    - JSON logs to stdout
    - OpenTelemetry Spring Boot Starter
    - Manual instrumentation with @WithSpan / OTel API
    - OTLP traces / metrics exporter

       logs(stdout/stderr)
             |
             v
  [FireLens / Fluent Bit Container]
    - awsfirelens log router
    - Datadog Fluent Bit output plugin
             |
             v
        Datadog Logs

       traces / metrics (OTLP)
             |
             v
  [Datadog Agent Container]
    - ECS_FARGATE=true
    - APM enabled
    - OTLP receiver enabled
             |
             v
        Datadog APM / Metrics

Optional / limited:
  CloudWatch Logs
    - log_router diagnostic logs
    - Datadog Agent diagnostic logs
    - fallback or compliance only if required
```

## 5. 検討した代替案

## 5.1 CloudWatch Logs と Datadog Logs の両方でアプリログを全量・長期保持する

### 内容

アプリログを CloudWatch Logs に送り、さらに CloudWatch Logs から Datadog Forwarder / Lambda で Datadog Logs に転送する。

### メリット

- 既存構成を大きく変えずに Datadog Logs へ連携できる。
- AWS 標準の CloudWatch Logs を引き続き利用できる。
- 移行期間中の安全性が高い。

### デメリット

- CloudWatch Logs と Datadog Logs の両方で取り込み・保存コストが発生しやすい。
- ログ閲覧場所が二重化し、運用がぶれやすい。
- Datadog 側へ到達するまでの経路が増え、遅延や障害点が増える。

### 判断

移行期や監査要件がある場合は許容するが、最終構成の第一候補にはしない。

## 5.2 通常ログは CloudWatch Logs、OpenTelemetry logs は Datadog Logs で見る

### 内容

既存 SLF4J ログは CloudWatch Logs に残し、OpenTelemetry logs のみを Datadog に送る。

### メリット

- 既存アプリログの流れをほぼ変更しない。
- Datadog Logs の取り込み量を抑えられる可能性がある。

### デメリット

- 障害調査で最も見たい通常ログが Datadog APM と分断される。
- traces / metrics / logs の相関が弱くなる。
- OpenTelemetry logs を通常ログの代替として使うことになり、SLF4J / Logback と二重管理になりやすい。
- OpenTelemetry Logs API は既存ログ API の置き換えではない。

### 判断

採用しない。

## 5.3 FireLens のみを使用し、Datadog Agent sidecar を使用しない

### 内容

FireLens / Fluent Bit だけを配置し、Datadog Agent sidecar を配置しない。

### メリット

- タスク内のサイドカー数を減らせる。
- Fargate の CPU / memory 消費を抑えられる可能性がある。

### デメリット

- FireLens はログルーターであり、OpenTelemetry traces / metrics の受け口ではない。
- Datadog APM、OTLP traces / metrics、Fargate コンテナメトリクスの収集に不足が生じる。
- 本プロジェクトの目的であるオブザーバビリティ実装としては不十分。

### 判断

採用しない。

## 5.4 Datadog Agent sidecar のみを使用し、FireLens を使用しない

### 内容

Datadog Agent sidecar だけで logs / traces / metrics をすべて処理しようとする。

### メリット

- サイドカーが 1 種類で済むように見える。

### デメリット

- ECS Fargate でアプリコンテナのログが `awslogs` ドライバーにより CloudWatch Logs に送られている場合、そのログは Datadog Agent から見えない。
- stdout / stderr ログの Datadog 転送には、FireLens または CloudWatch Logs 経由の Forwarder が必要になる。

### 判断

採用しない。

## 5.5 OpenTelemetry Collector sidecar を使用し、Datadog Agent sidecar を使用しない

### 内容

アプリケーションから OpenTelemetry Collector sidecar に OTLP で送信し、Collector の Datadog exporter で Datadog に転送する。

### メリット

- OpenTelemetry 中心の構成になり、ベンダー中立性が高い。
- Collector の processor / exporter を使った柔軟な処理ができる。

### デメリット

- Datadog Agent による Fargate 連携、APM 連携、Datadog エコシステムとの親和性を別途検討する必要がある。
- Collector の設定、運用、バージョン管理が別途必要になる。
- 今回は Datadog を主要基盤として採用する前提であるため、Datadog Agent sidecar の方が構成意図が明確である。

### 判断

将来の標準化候補としては残すが、本 ADR では採用しない。

## 5.6 OpenTelemetry Logs API をアプリケーションの業務ログ API として直接使用する

### 内容

`log.info()` の代わりに OpenTelemetry Logs API をアプリケーションコードから直接呼び出す。

### メリット

- OpenTelemetry のログデータモデルに直接合わせられる。

### デメリット

- 既存の SLF4J / Logback と二重化する。
- Java アプリケーションの標準的なログ実装から外れる。
- OpenTelemetry Logs API は、既存ログフレームワークから OpenTelemetry へ橋渡しする用途が主であり、通常ログ API の置き換えとして使うべきではない。
- Datadog Agent の OTLP logs ingestion は予期しないログ課金を避けるためデフォルト無効であり、明示的な有効化が必要である。

### 判断

採用しない。業務ログは SLF4J + Logback で出し、trace context を付与して Datadog Logs に送る。

## 6. 実装方針

## 6.1 Spring Boot アプリケーション

### 6.1.1 ログ

- SLF4J + Logback を継続する。
- ログは stdout に出す。
- 本番では JSON ログを標準とする。
- `trace_id` / `span_id` をログに含める。
- secrets、token、password、credential、PII を出力しない。
- `orderId` など業務 ID は、機密性と監査要件を確認したうえで扱う。

### 6.1.2 OpenTelemetry

- OpenTelemetry Spring Boot Starter を使用する。
- `@WithSpan` を Service 境界に限定して使用する。
- `@SpanAttribute` は低カーディナリティで調査価値がある項目に限定する。
- 直接 `Span.current().addEvent(...)` を使う場合は、イベント名の命名規則を設ける。
- 直接 `Tracer` を使う場合は、共通ヘルパー化する。

### 6.1.3 span 命名規則

span 名は、技術的なメソッド名だけでなく、業務的に意味がわかる名前にする。

例:

- `order.create`
- `payment.authorize`
- `contract.approve`
- `batch.importCustomerRecord`

避ける例:

- `execute`
- `doProcess`
- `method1`
- `OrderService.createOrder` のみで業務意味が不足するもの

### 6.1.4 span attributes 命名規則

属性名は dot 区切りにする。

例:

- `business.operation`
- `order.type`
- `payment.method`
- `external.system`
- `result.status`
- `error.category`

避ける例:

- `order.id` を metrics attributes に入れること
- `customer.email`
- `access.token`
- `password`
- `raw.request.body`

## 6.2 ECS Fargate タスク定義

### 6.2.1 コンテナ構成

最終構成では、少なくとも次のコンテナを同一タスク定義に含める。

1. application container
2. Datadog Agent container
3. FireLens / Fluent Bit log router container

### 6.2.2 application container

- ログドライバー: `awsfirelens`
- Datadog log attributes:
  - `dd_service`
  - `dd_source`
  - `dd_tags`
  - `dd_message_key`
- OpenTelemetry exporter endpoint:
  - OTLP/HTTP を使う場合: `http://localhost:4318`
  - OTLP/gRPC を使う場合: `http://localhost:4317`
- OpenTelemetry resource attributes:
  - `service.name`
  - `service.version`
  - `deployment.environment`

### 6.2.3 Datadog Agent container

- `ECS_FARGATE=true`
- `DD_APM_ENABLED=true`
- `DD_SITE` を Datadog site に合わせて設定する。
- `DD_API_KEY` は Secrets Manager 等から注入し、タスク定義に平文で書かない。
- OTLP receiver を有効化する。
- OTLP logs ingestion は原則無効のままにする。

### 6.2.4 FireLens / Fluent Bit container

- `firelensConfiguration.type=fluentbit`
- `enable-ecs-log-metadata=true`
- Datadog Fluent Bit output plugin を使用する。
- Datadog API key は secretOptions で渡す。
- `compress=gzip` を検討する。
- Log router のメモリ不足によるログロストを避けるため、タスク CPU / memory を見積もる。

## 7. コスト方針

## 7.1 基本方針

- Datadog Logs を主要な調査基盤とする。
- CloudWatch Logs と Datadog Logs の二重全量長期保存を避ける。
- 本番ログの出力量を制御する。
- Datadog に送るログと、Datadog でインデックスするログを分けて考える。
- metrics attributes の高カーディナリティ化を防ぐ。
- span attributes の高カーディナリティ化にも注意する。

## 7.2 Datadog Logs

Datadog Logs では、取り込むログ量、インデックス対象量、保持期間がコストに影響する。したがって、次を設計対象とする。

- Index の分割
- Exclusion Filter
- retention
- daily quota
- DEBUG ログの除外
- health check / liveness / readiness 系ログの扱い
- access log を全量保存するか、サンプリングするか

## 7.3 CloudWatch Logs

CloudWatch Logs を残す場合は、保持期間を明示する。

例:

- application logs: 原則 FireLens -> Datadog のため CloudWatch には送らない
- log_router logs: 7 日または 14 日
- datadog-agent logs: 7 日または 14 日
- compliance logs: 監査要件に従う

## 8. セキュリティ・コンプライアンス方針

次の情報は logs / traces / metrics に出力しない。

- パスワード
- アクセストークン
- refresh token
- API key
- secret key
- cookie 全文
- Authorization header 全文
- 個人番号、クレジットカード番号、銀行口座番号などの高リスク情報
- メールアドレス、電話番号、住所など、出力可否判断が必要な PII
- request / response body 全文

業務監査として長期保管が必要な情報は、アプリケーションログではなく、監査テーブル、イベントストア、S3 アーカイブなどの別設計で扱う。

## 9. 運用方針

## 9.1 主な閲覧先

- 障害調査: Datadog APM + Datadog Logs
- 性能調査: Datadog APM + Datadog Metrics
- ログ検索: Datadog Logs
- AWS リソース状態: Datadog Infrastructure / AWS integration / 必要に応じて AWS Console
- FireLens / Agent 自体の起動失敗調査: CloudWatch Logs または Datadog Logs

## 9.2 アラート

少なくとも次を監視する。

- error rate
- latency p95 / p99
- failed business operation count
- external API failure count
- Datadog Agent health
- FireLens / Fluent Bit error
- log volume anomaly
- indexed logs volume anomaly
- custom metrics cardinality anomaly

## 9.3 ダッシュボード

少なくとも次を用意する。

- service overview
- latency / throughput / errors
- business operation success / failure
- external dependency health
- ECS Fargate task health
- logs ingestion / indexed logs usage
- APM trace volume / error traces

## 10. 影響

## 10.1 良い影響

- Datadog 上で logs / traces / metrics を相関できる。
- 業務処理の流れを span として可視化できる。
- SLF4J + Logback を維持するため、既存コードへの影響が小さい。
- OpenTelemetry API を使うため、手動計装コードは Datadog 専用 API への依存を避けられる。
- FireLens により、Fargate の stdout / stderr ログを Datadog Logs へ直接送れる。
- Datadog Agent sidecar により、APM、OTLP traces / metrics、Fargate メトリクスを扱える。

## 10.2 悪い影響・注意点

- ECS Fargate タスク内のコンテナ数が増える。
- Datadog Agent と FireLens の CPU / memory を見積もる必要がある。
- ログ量が多い場合、Datadog Logs のコストが増える。
- span / metrics attributes の設計を誤ると、高カーディナリティによるコストや検索性低下が起こる。
- `@WithSpan` は Spring AOP proxy ベースのため、同一クラス内の自己呼び出しでは効かない場合がある。
- FireLens の設定ミスによりログが Datadog に届かない可能性がある。

## 10.3 リスクと対策

| リスク | 対策 |
|---|---|
| ログ量増加による Datadog コスト増 | DEBUG 抑止、Index / Exclusion Filter / daily quota、ログ量監視 |
| CloudWatch と Datadog の二重課金 | 最終構成ではアプリログを FireLens -> Datadog に寄せる |
| 手動 span による業務コード肥大化 | `@WithSpan` 優先、共通ヘルパー化、span 粒度レビュー |
| PII / secret 漏えい | ログ・span・metrics の出力禁止項目を明文化し、レビュー観点に入れる |
| trace と log が相関できない | `trace_id` / `span_id` を JSON ログの top-level field として出す |
| FireLens 障害によるログ欠落 | Fluent Bit エラー監視、バッファ設定、Agent/FireLens の診断ログ保持 |
| Datadog Agent OTLP receiver 設定ミス | 起動時チェック、OTLP health check、サンプルトレース送信テスト |

## 11. 採用基準 / 完了条件

本 ADR の決定が実装されたと判断する条件は次の通りである。

1. Spring Boot アプリが `SLF4J + Logback` で JSON ログを stdout に出力している。
2. アプリログに `service` / `env` / `version` / `trace_id` / `span_id` が含まれている。
3. アプリログが FireLens / Fluent Bit 経由で Datadog Logs に到達している。
4. CloudWatch Logs がアプリログの主要閲覧先ではなくなっている、または保持期間・用途が明示されている。
5. OpenTelemetry Spring Boot Starter が導入されている。
6. 重要な業務 Service 境界に `@WithSpan` または同等の手動計装が実装されている。
7. traces / metrics が OTLP 経由で Datadog Agent sidecar に送信され、Datadog APM / Metrics で確認できる。
8. Datadog 上で trace から該当ログへ遷移できる。
9. Datadog 上で logs / traces / metrics が同じ `env` / `service` / `version` で紐づく。
10. Datadog Logs の Index / Exclusion Filter / retention / quota の方針が定義されている。
11. PII / secret を出さないための実装・レビュー観点が明文化されている。

## 12. 今後の検討事項

- Datadog Agent sidecar と OpenTelemetry Collector sidecar の使い分けを将来再評価するか。
- Datadog Observability Pipelines を導入して、ログの前処理・マスキング・ルーティングを行うか。
- 監査ログをアプリケーションログとは別に S3 / DB / イベントストアへ保存するか。
- APM sampling rate、tail-based sampling、error trace の保持方針をどうするか。
- Datadog Sensitive Data Scanner を利用するか。
- ECS タスク数が増えた場合の Datadog Agent / FireLens sidecar コストをどう見積もるか。

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
- AWS CloudWatch Pricing: https://aws.amazon.com/cloudwatch/pricing/
- Datadog Pricing: https://www.datadoghq.com/pricing/
