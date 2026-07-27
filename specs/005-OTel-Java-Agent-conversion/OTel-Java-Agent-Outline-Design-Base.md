# OpenTelemetry Java Agent対応

#### 質問

現状のSpring BootアプリをAWS ECS Fargateで動かして、Datadog Agent sidecarを使ってDatadog APMにテレメトリデータを送っています。
OpenTelemetryの対応のために以下のような仕様になっています。

に加えて以下の機能を追加したいと考えています。

1. `Trace / Span`に`JDBC instrumentation`を追加したい。
2. `Metrics`に`JDBC instrumentation`を追加したい。
3. `Metrics`に`Runtime Metrics`を追加したい。

opentelemetry-javaagent.jarを使えば、Micrometer、Spring Boot OTelやなどを使わずに、以下の機能と上記の1,2,3の機能を実現できるのでしょうか？

もし、可能なら、以下の`## Signal 別の流れ`をopentelemetry-javaagent.jarを使った場合に書き換えて出力してください。

---

## Signal 別の流れ

| Signal | 生成元 | アプリ内の主役 | ECS内の経路 | Datadog側の到達先 | 現行の注意点 |
| --- | --- | --- | --- | --- | --- |
| Logs | SLF4J / Logback | 構造化ログ、MDC、`trace_id` / `span_id` | app stdout -> FireLens | Datadog Logs | OTLP logs は使っていない |
| Trace / Span | Spring Boot OTel、 |  |  |  |  |
| Micrometer Tracing、 |  |  |  |  |  |
| 独自 AOP | OpenTelemetry SDK / exporter | app -> OTLP/gRPC `4317` -> Datadog Agent | Datadog APM | DB/JPA/JDBC span は現状見えていない |  |
| Metrics | Micrometer `MeterRegistry` | `BusinessMetricsService`、Actuator / Micrometer | app -> OTLP/HTTP `4318` -> Datadog Agent | Datadog Metrics | 業務 metrics は Micrometer API に統一 |
| OTLP送信 | アプリ JVM | OTel exporter / Micrometer OTLP registry | `localhost:4317` / `localhost:4318` | Datadog Agent sidecar | logs は OTLP 送信しない |
| OTLP受信/転送 | Datadog Agent sidecar | Datadog Agent | `4317` traces、`4318` metrics | Datadog APM / Metrics | span 自体を自動生成する役ではない |

結論としては、**かなりの部分は `opentelemetry-javaagent.jar` だけで実現できます**。

ただし、**完全に Micrometer を外してよいか**は、`BusinessMetricsService` のような **業務 metrics** をどう出しているかで変わります。

OpenTelemetry Java Agent は JVM 起動時に付ける Java agent で、Spring MVC、HTTP、JDBC などのライブラリに自動で入り込み、telemetry を生成できます。公式ドキュメントでも、インバウンドリクエスト、アウトバウンド HTTP、database calls などを取得できると説明されています。(OpenTelemetry) また、Datadog Agent は OTLP traces / metrics を gRPC または HTTP で取り込めます。(Datadog 監視)

## まず結論

| やりたいこと | `opentelemetry-javaagent.jar` だけで可能か | 補足 |
| --- | --- | --- |
| Spring MVC / HTTP の trace/span | 可能 | Spring Web MVC は HTTP server spans / metrics / controller spans の対象です。(OpenTelemetry) |
| JDBC query span | 可能 | JDBC は Database Client Spans の対象です。(OpenTelemetry) |
| JDBC metrics | 可能。ただし注意あり | JDBC は Database Client Metrics の対象ですが、DB client metrics は semantic convention stability の opt-in が必要です。(OpenTelemetry) |
| Runtime Metrics / JVM metrics | 可能 | Datadog 公式でも Java の OpenTelemetry automatic instrumentation では runtime metrics がデフォルト有効と説明されています。(Datadog 監視) |
| ログの `trace_id` / `span_id` 付与 | 可能 | Java Agent は current span の `trace_id` / `span_id` / `trace_flags` を MDC に注入できます。(GitHub) |
| 業務 metrics | **Micrometerを完全に外すなら不可** | `BusinessMetricsService` が Micrometer API で `Counter` などを出しているなら、Micrometerを消すと業務 metrics も消えます。OTel Metrics APIへ書き換えるか、Micrometer APIだけ残す必要があります。 |
| Service public method span | デフォルトでは不可 | Java Agent は Controller / JDBC などは取れますが、任意の Service public method すべてを自動で span にするわけではありません。代替として methods instrumentation や `@WithSpan`、既存AOPを使います。(GitHub) |

なので、判断としてはこうです。

```
Spring Boot OTel / Micrometer Tracing は、Java Agent中心に置き換え可能。

ただし、
  業務 metrics を Micrometer API で出しているなら Micrometer API は残す。
  Service public method span が必要なら、独自AOPか methods instrumentation を残す。
```

---

## `opentelemetry-javaagent.jar` を使った場合の Signal 別の流れ

## Signal 別の流れ

| Signal | 生成元 | アプリ内の主役 | ECS内の経路 | Datadog側の到達先 | Java Agent 構成での注意点 |
| --- | --- | --- | --- | --- | --- |
| Logs | SLF4J / Logback、OpenTelemetry Java Agent の MDC instrumentation | 構造化ログ、MDC、`trace_id` / `span_id` | app stdout -> FireLens | Datadog Logs | OTLP logs は使わない。`OTEL_LOGS_EXPORTER=none` を明示する。Java Agent はログ送信ではなく、MDC への trace/span ID 注入に使う |
| Trace / Span
(自動計装) | `opentelemetry-javaagent.jar` の自動計装：Servlet / Spring Web MVC / HTTP client / JDBC など | Java Agent 内蔵の OpenTelemetry SDK / OTLP exporter | app -> OTLP/gRPC `4317` -> Datadog Agent | Datadog APM | Spring Boot OTel / Micrometer Tracing は不要にできる。
HTTPリクエスト、Controller、外部HTTP呼び出し、JDBC query span は自動で見える想定。
ただし JPA span そのものではなく、主に JPA / Hibernate の裏で実行される JDBC span として見える。 |
| Trace / Span
(業務メソッド) | 必要に応じて
Methods instrumentation /
`@WithSpan` /
既存AOP /
手動 span /
作成 | 業務コード側の指定 + Java Agent 内蔵の OpenTelemetry SDK / OTLP exporter | app -> OTLP/gRPC `4317` -> Datadog Agent | Datadog APM | Service public method span など、業務処理単位の span は Java Agent だけで必ず自動生成されるわけではない。
必要なメソッドだけを明示的に span 化する。
作りすぎると trace が読みにくくなり、送信量・保存量も増える  |
| Metrics | `opentelemetry-javaagent.jar` の自動計装：HTTP metrics、JDBC Database Client Metrics、HikariCP などの DB pool metrics、JVM Runtime Metrics。業務 metrics は OTel Metrics API または Micrometer API | Java Agent 内蔵の OpenTelemetry MeterProvider / OTLP metric exporter。業務 metrics を Micrometerで残す場合は Micrometer bridge | app -> OTLP/HTTP `4318` -> Datadog Agent | Datadog Metrics | JDBC metrics は semantic convention stability opt-in が必要。Runtime Metrics は Java automatic instrumentation ではデフォルト有効。業務 metrics を Micrometer API に統一するなら Micrometer は完全には外さない |
| Metrics
(自動計装) | `opentelemetry-javaagent.jar` の自動計装：HTTP metrics、JDBC Database Client Metrics、HikariCP などの DB pool metrics、JVM Runtime Metrics | Java Agent 内蔵の OpenTelemetry MeterProvider / OTLP metric exporter | app -> OTLP/HTTP `4318` -> Datadog Agent | Datadog Metrics | JDBC metrics は semantic convention stability opt-in が必要。
Runtime Metrics は Java automatic instrumentation ではデフォルト有効。
Micrometer 由来の metrics と重複しないように注意。 |
| Metrics
(業務メソッド) | 業務コードから明示的に記録：`BusinessMetricsService`、OTel Metrics API、または Micrometer API | OTel Metrics API を使う場合は Java Agent 内蔵の OpenTelemetry MeterProvider / OTLP metric exporter。Micrometer API を残す場合は Micrometer `MeterRegistry` / Micrometer bridge | app -> OTLP/HTTP `4318` -> Datadog Agent | Datadog Metrics | 注文数、処理成功数、業務エラー数などの業務 metrics は自動では作られない。
Micrometer API に統一するなら Micrometer は完全には外さない。
OTel Metrics API に移行するなら業務 metrics の実装変更が必要。 |
| OTLP送信 | アプリ JVM + `opentelemetry-javaagent.jar` | Java Agent 内蔵 OTel SDK / OTLP exporter | traces: `localhost:4317`、metrics: `localhost:4318` | Datadog Agent sidecar | Java Agent 2.x は既定 protocol が `http/protobuf` なので、traces を gRPC `4317` に送るなら signal別に protocol / endpoint を明示する |
| OTLP受信/転送 | Datadog Agent sidecar | Datadog Agent OTLP receiver | `4317` traces、`4318` metrics | Datadog APM / Metrics | Datadog Agent は span / metrics を自動生成する役ではなく、受信・変換・転送する役。OTLP logs は使わない |

#### Trace / Span 解説

この2行は、**Trace / Span を「自動で取れるもの」と「自分たちで指定して取るもの」に分けた表現**です。

---

## 1. `Trace / Span (自動計装)`

対象の行はこれです。

| Signal | 生成元 | アプリ内の主役 | ECS内の経路 | Datadog側の到達先 | Java Agent 構成での注意点 |
| --- | --- | --- | --- | --- | --- |
| `Trace / Span (自動計装)` | `opentelemetry-javaagent.jar` の自動計装：Servlet / Spring Web MVC / HTTP client / JDBC など | Java Agent 内蔵の OpenTelemetry SDK / OTLP exporter | app -> OTLP/gRPC `4317` -> Datadog Agent | Datadog APM | Spring Boot OTel / Micrometer Tracing は不要にできる。HTTPリクエスト、Controller、外部HTTP呼び出し、JDBC query span は自動で見える想定。ただし JPA span そのものではなく、主に JPA / Hibernate の裏で実行される JDBC span として見える |

これは、**`opentelemetry-javaagent.jar` が、Spring Boot アプリのよくある処理を自動で span にする**という意味です。OpenTelemetry Java Agent は Java アプリに `-javaagent` で取り付け、Servlet、Spring、JDBC などのライブラリやフレームワークに対して自動的に telemetry を取得する仕組みです。公式ドキュメントでも、inbound requests、outbound HTTP calls、database calls などを取得できると説明されています。(OpenTelemetry)

### `生成元`

`生成元` は、**span を作るもの**です。

ここでは、

```
opentelemetry-javaagent.jar
```

が生成元です。

つまり、アプリコードの中に毎回 span 作成処理を書かなくても、Java Agent が自動で span を作ります。

この行に出てくる対象は以下です。

| 名称 | 意味 | 何が span になるか |
| --- | --- | --- |
| `Servlet` | Java Web アプリのHTTP入口 | `GET /orders`、`POST /orders` などのHTTPリクエスト |
| `Spring Web MVC` | Spring Boot の Controller まわり | `OrderController.createOrder()` など |
| `HTTP client` | アプリから外部APIを呼ぶ処理 | 決済API、在庫APIなどへのHTTP呼び出し |
| `JDBC` | Java からDBにSQLを投げる仕組み | `SELECT`、`INSERT`、`UPDATE` などのSQL実行 |

例えば、Datadog APM では次のような trace が見える想定です。

```
POST /orders                         ← Servlet / HTTPリクエストの span
  └─ OrderController.createOrder      ← Spring Web MVC / Controller の span
      └─ GET payment-api /charge      ← HTTP client / 外部HTTP呼び出しの span
      └─ SELECT customer ...          ← JDBC query span
      └─ INSERT orders ...            ← JDBC query span
```

---

### `アプリ内の主役`

```
Java Agent 内蔵の OpenTelemetry SDK / OTLP exporter
```

これは、**span を管理して外へ送る担当**です。

分けるとこうです。

| 名称 | 意味 |
| --- | --- |
| `Java Agent 内蔵` | `opentelemetry-javaagent.jar` の中に入っている、という意味 |
| `OpenTelemetry SDK` | 作られた span を保持・整理・処理する本体 |
| `OTLP exporter` | span を OTLP 形式で外部へ送る送信部品 |

つまり、

```
Java Agent が span を作る
  ↓
Java Agent 内蔵の OpenTelemetry SDK が span を管理する
  ↓
OTLP exporter が Datadog Agent に送る
```

という流れです。

---

### `ECS内の経路`

```
app -> OTLP/gRPC 4317 -> Datadog Agent
```

これは、ECS Fargate の中で trace/span がどう流れるかです。

```
Spring Boot アプリコンテナ
  ↓ OTLP/gRPC 4317
Datadog Agent sidecar
  ↓
Datadog APM
```

`OTLP` は OpenTelemetry の標準的な送信形式です。

`gRPC 4317` は、OTLP/gRPC のデフォルトポートとして使われることが多いポートです。Datadog Agent は OTLP traces / metrics を gRPC または HTTP で取り込め、Datadog ドキュメントでは gRPC のデフォルトポートとして `4317`、HTTP のデフォルトポートとして `4318` が示されています。(Datadog)

---

### `Datadog側の到達先`

```
Datadog APM
```

これは、作られた trace/span が Datadog の **APM** に表示される、という意味です。

APM では、たとえば以下を見ます。

```
どのAPIが遅いか
どのControllerで時間がかかったか
どの外部API呼び出しが遅いか
どのSQLが遅いか
```

---

### `Java Agent 構成での注意点`

この部分です。

```
Spring Boot OTel / Micrometer Tracing は不要にできる。
HTTPリクエスト、Controller、外部HTTP呼び出し、JDBC query span は自動で見える想定。
ただし JPA span そのものではなく、主に JPA / Hibernate の裏で実行される JDBC span として見える。
```

意味は3つあります。

1つ目は、**Trace / Span の生成については、Spring Boot OTel / Micrometer Tracing を使わなくても、Java Agent に寄せられる**という意味です。

```
従来:
Spring Boot OTel / Micrometer Tracing が span 作成に関与

Java Agent構成:
opentelemetry-javaagent.jar が span 作成の中心になる
```

2つ目は、**HTTPリクエスト、Controller、外部HTTP呼び出し、JDBC query span は自動で見える想定**という意味です。

```
HTTPリクエスト
Controller
外部HTTP呼び出し
JDBC query span
```

このあたりは、Java Agent の自動計装で取得しやすい範囲です。

3つ目は、**JPA / Hibernate の見え方に注意**という意味です。

Spring Boot アプリでは、DBアクセスがよくこうなっています。

```
Repository / JPA
  ↓
Hibernate
  ↓
JDBC
  ↓
Database
```

そのため、APM上では、

```
JPA span
Hibernate span
```

として見えるというより、

```
SELECT ...
INSERT ...
UPDATE ...
```

のような **JDBC query span** として見える、という理解が安全です。

---

## 2. `Trace / Span (業務メソッド)`

対象の行はこれです。

| Signal | 生成元 | アプリ内の主役 | ECS内の経路 | Datadog側の到達先 | Java Agent 構成での注意点 |
| --- | --- | --- | --- | --- | --- |
| `Trace / Span (業務メソッド)` | 必要に応じて Methods instrumentation / `@WithSpan` / 既存AOP / 手動 span 作成 | 業務コード側の指定 + Java Agent 内蔵の OpenTelemetry SDK / OTLP exporter | app -> OTLP/gRPC `4317` -> Datadog Agent | Datadog APM | Service public method span など、業務処理単位の span は Java Agent だけで必ず自動生成されるわけではない。必要なメソッドだけを明示的に span 化する。作りすぎると trace が読みにくくなり、送信量・保存量も増える |

これは、**自社アプリ固有の Service メソッドなどを span として見たい場合の話**です。

---

### `生成元`

```
必要に応じて Methods instrumentation / @WithSpan / 既存AOP / 手動 span 作成
```

ここでの `生成元` は、Java Agent が完全自動で作る span ではなく、**自分たちが「この業務メソッドを span にしたい」と指定する仕組み**です。

例えば、こういうメソッドです。

```java
OrderService.createOrder()
PaymentService.authorize()
InventoryService.reserve()
```

Java Agent は HTTP や JDBC などの有名なライブラリは自動で見ますが、**あなたのアプリ独自の Service public method すべてを、必ず自動で span にするわけではありません**。

そのため、以下の方法を使います。

| 名称 | 意味 |
| --- | --- |
| `Methods instrumentation` | 設定で「このクラス・このメソッドを span にする」と指定する方法 |
| `@WithSpan` | span にしたいメソッドにアノテーションを付ける方法 |
| `既存AOP` | 既存の `PublicMethodTelemetryAspect` のような AOP で span を作る方法 |
| `手動 span 作成` | OpenTelemetry API を使ってコード内で明示的に span を作る方法 |

`@WithSpan` は、付けたメソッドが呼ばれるたびに、その実行時間や例外を表す span を作るためのアノテーションです。また、コードを変更できない場合には `OTEL_INSTRUMENTATION_METHODS_INCLUDE` で特定メソッドを span 化できます。(OpenTelemetry)

---

### `アプリ内の主役`

```
業務コード側の指定 + Java Agent 内蔵の OpenTelemetry SDK / OTLP exporter
```

これは、**span を作る対象は業務コード側で指定し、送信処理は Java Agent 側が担当する**という意味です。

自動計装との違いはここです。

```
自動計装:
  Java Agent が「ここはHTTP」「ここはJDBC」と自動で判断して span を作る

業務メソッド:
  自分たちが「このServiceメソッドをspanにしたい」と指定する
```

例えば `@WithSpan` を使うなら、

```java
@WithSpan
public void createOrder() {
    ...
}
```

のようにします。

すると、trace はこう見える可能性があります。

```
POST /orders
  └─ OrderService.createOrder          ← 業務メソッド span
      └─ PaymentService.authorize      ← 業務メソッド span
      └─ INSERT orders ...             ← JDBC query span
```

---

### `ECS内の経路`

```
app -> OTLP/gRPC 4317 -> Datadog Agent
```

ここは `Trace / Span (自動計装)` と同じです。

業務メソッド span も、最終的な送信経路は同じです。

```
Spring Boot アプリコンテナ
  ↓ OTLP/gRPC 4317
Datadog Agent sidecar
  ↓
Datadog APM
```

違いは、**span の作られ方**だけです。

```
自動計装 span:
  Java Agent が自動で作る

業務メソッド span:
  @WithSpan / Methods instrumentation / AOP / 手動 span 作成で明示的に作る
```

---

### `Datadog側の到達先`

```
Datadog APM
```

これも `Trace / Span (自動計装)` と同じです。

自動計装の span も、業務メソッドの span も、最終的には Datadog APM の trace 内で見えます。

例えば、

```
POST /orders
  ├─ OrderController.createOrder       ← 自動計装
  ├─ OrderService.createOrder          ← 業務メソッド
  │   ├─ PaymentService.authorize      ← 業務メソッド
  │   └─ INSERT orders ...             ← 自動計装 JDBC
```

のように、1つの trace の中で混ざって見えます。

---

### `Java Agent 構成での注意点`

この部分です。

```
Service public method span など、業務処理単位の span は Java Agent だけで必ず自動生成されるわけではない。
必要なメソッドだけを明示的に span 化する。
作りすぎると trace が読みにくくなり、送信量・保存量も増える。
```

意味は3つあります。

1つ目は、**Service public method span は自動では足りない場合がある**ということです。

```
Controller span
JDBC query span
```

は自動で見えても、

```
OrderService.createOrder span
PaymentService.authorize span
InventoryService.reserve span
```

は、自分たちで指定しないと出ない場合があります。

2つ目は、**必要なメソッドだけを span 化する**ということです。

全部の public method を span にすると、trace がこうなりがちです。

```
POST /orders
  ├─ validateInput
  ├─ mapRequest
  ├─ checkCustomer
  ├─ checkInventory
  ├─ calculatePrice
  ├─ createOrder
  ├─ createOrderItem
  ├─ saveOrder
  ├─ saveOrderItem
  └─ ...
```

細かすぎると、かえって見づらくなります。

3つ目は、**送信量・保存量が増える**ということです。

span が増えると、

```
アプリから Datadog Agent に送るデータ量
Datadog に保存される trace の量
APM上で見る情報量
```

が増えます。

そのため、業務メソッド span は、

```
障害調査で意味がある単位
処理時間を見たい単位
ビジネス上重要な処理単位
```

に絞るのが現実的です。

---

## 2行の違いをまとめると

| 観点 | `Trace / Span (自動計装)` | `Trace / Span (業務メソッド)` |
| --- | --- | --- |
| 誰が span を作るか | `opentelemetry-javaagent.jar` が自動で作る | 自分たちが `@WithSpan`、Methods instrumentation、既存AOP、手動 span 作成で指定する |
| 対象 | Servlet、Spring Web MVC、HTTP client、JDBC など | Service public method などの業務処理 |
| 例 | HTTPリクエスト、Controller、外部HTTP呼び出し、JDBC query span | `OrderService.createOrder()`、`PaymentService.authorize()` |
| 設定の考え方 | Java Agent に任せる | 必要なメソッドだけ明示的に選ぶ |
| 注意点 | JPA / Hibernate そのものというより、主に裏側の JDBC span として見える | 作りすぎると trace が読みにくくなり、送信量・保存量も増える |

---

## 一番わかりやすいイメージ

この2行を合わせると、最終的にはこういう trace を目指している、ということです。

```
POST /orders                                      ← Trace / Span (自動計装): Servlet
  └─ OrderController.createOrder                  ← Trace / Span (自動計装): Spring Web MVC
      └─ OrderService.createOrder                 ← Trace / Span (業務メソッド): @WithSpan / AOP など
          └─ PaymentService.authorize             ← Trace / Span (業務メソッド): @WithSpan / AOP など
              └─ POST payment-api /charge         ← Trace / Span (自動計装): HTTP client
          └─ SELECT customer ...                  ← Trace / Span (自動計装): JDBC
          └─ INSERT orders ...                    ← Trace / Span (自動計装): JDBC
```

つまり、

```
Trace / Span (自動計装)
= Java Agent がライブラリやフレームワークを自動で見える化する部分

Trace / Span (業務メソッド)
= 自分たちが重要だと思う業務処理を明示的に見える化する部分
```

という分け方です。

#### Metrics 解説

この2行は、**Metrics を「自動で集める技術系の数値」と「業務コードから明示的に記録する数値」に分けたもの**です。

まず前提として、**Metrics は span とは違います**。

```
Trace / Span
= 1回のリクエストや1回のSQL実行の詳細

Metrics
= 何回起きたか、平均何msか、現在いくつ使っているか、などの集計値
```

---

## 1. `Metrics (自動計装)`

対象の行はこれです。

| Signal | 生成元 | アプリ内の主役 | ECS内の経路 | Datadog側の到達先 | Java Agent 構成での注意点 |
| --- | --- | --- | --- | --- | --- |
| Metrics(自動計装) | `opentelemetry-javaagent.jar` の自動計装：HTTP metrics、JDBC Database Client Metrics、HikariCP などの DB pool metrics、JVM Runtime Metrics | Java Agent 内蔵の OpenTelemetry MeterProvider / OTLP metric exporter | app -> OTLP/HTTP `4318` -> Datadog Agent | Datadog Metrics | JDBC metrics は semantic convention stability opt-in が必要。Runtime Metrics は Java automatic instrumentation ではデフォルト有効。Micrometer 由来の metrics と重複しないように注意 |

これは、**`opentelemetry-javaagent.jar` が、アプリやライブラリの状態を自動で数値化する**という意味です。OpenTelemetry Java Agent は多くのライブラリやフレームワークを自動計装でき、JDBC は `Database Client Spans` と `Database Client Metrics`、HikariCP は `Database Pool Metrics`、Java Platform は `JVM Runtime Metrics` の対象として記載されています。(OpenTelemetry)

### `生成元`

```
opentelemetry-javaagent.jar の自動計装
```

ここでの `生成元` は、**metrics を自動で作るもの**です。

例えば、以下のような metrics が対象です。

| 名称 | 意味 | 例 |
| --- | --- | --- |
| `HTTP metrics` | HTTPリクエストに関する数値 | リクエスト数、処理時間、ステータスコード別件数など |
| `JDBC Database Client Metrics` | DBアクセスに関する数値 | DB操作時間、DB呼び出し回数など |
| `HikariCP などの DB pool metrics` | DBコネクションプールに関する数値 | 使用中コネクション数、空きコネクション数、待ち時間など |
| `JVM Runtime Metrics` | JVM自体の状態 | heap使用量、GC、スレッド数、ロード済みクラス数など |

たとえば Datadog Metrics では、次のようなグラフを見るイメージです。

```
HTTPリクエスト数
HTTPレスポンスタイム
DBクエリ時間
HikariCPの使用中コネクション数
JVM heap使用量
GC回数
スレッド数
```

Datadog の OpenTelemetry Runtime Metrics ドキュメントでも、Java の OpenTelemetry automatic instrumentation では runtime metrics がデフォルト有効で、JVM memory、threads、classes、CPU などが Datadog 側にマッピングされると説明されています。(Datadog)

---

## `アプリ内の主役`

```
Java Agent 内蔵の OpenTelemetry MeterProvider / OTLP metric exporter
```

ここは、**metrics をアプリ内で扱い、外へ送る担当**です。

分けるとこうです。

| 名称 | 意味 |
| --- | --- |
| `Java Agent 内蔵` | `opentelemetry-javaagent.jar` の中に入っている |
| `OpenTelemetry MeterProvider` | metrics を作成・管理する OpenTelemetry 側の本体 |
| `OTLP metric exporter` | metrics を OTLP 形式で外部へ送る部品 |

流れはこうです。

```
opentelemetry-javaagent.jar が metrics を自動生成
  ↓
OpenTelemetry MeterProvider が metrics を管理
  ↓
OTLP metric exporter が Datadog Agent に送信
```

---

## `ECS内の経路`

```
app -> OTLP/HTTP 4318 -> Datadog Agent
```

これは、ECS Fargate 内で metrics がどう流れるかです。

```
Spring Boot アプリコンテナ
  ↓ OTLP/HTTP 4318
Datadog Agent sidecar
  ↓
Datadog Metrics
```

Datadog Agent は OTLP traces / metrics を gRPC または HTTP で取り込めます。Datadog 公式ドキュメントでは、OTLP/gRPC のデフォルトポートは `4317`、OTLP/HTTP のデフォルトポートは `4318` と説明されています。(Datadog)

---

## `Datadog側の到達先`

```
Datadog Metrics
```

これは、送信された metrics が Datadog の **Metrics** として扱われる、という意味です。

APM の trace 画面というより、グラフ、ダッシュボード、モニター、アラートなどで使う数値データです。

---

## `Java Agent 構成での注意点`

### `JDBC metrics は semantic convention stability opt-in が必要`

これは少し難しいですが、簡単に言うと、

```
DB metrics の名前や属性のルールを、新しい安定版のルールで出すには設定が必要
```

という意味です。

OpenTelemetry では database metrics の安定版 semantic conventions があり、既存の計装が安定版を出すには `OTEL_SEMCONV_STABILITY_OPT_IN` で `database` や `database/dup` を指定する方式が説明されています。(OpenTelemetry)

例としては、以下のような設定です。

```bash
OTEL_SEMCONV_STABILITY_OPT_IN=database
```

または移行期間として両方出すなら、

```bash
OTEL_SEMCONV_STABILITY_OPT_IN=database/dup
```

ただし、実際にどちらを使うかは、利用している `opentelemetry-javaagent.jar` のバージョンと、Datadog 側で見たいメトリクス名に合わせて確認した方が安全です。

### `Runtime Metrics は Java automatic instrumentation ではデフォルト有効`

これは、

```
JVMのメモリ、GC、スレッド数などは、Java Agentで基本的に自動で取れる
```

という意味です。

なので、Runtime Metrics 用に毎回アプリコードを書く必要は通常ありません。

### `Micrometer 由来の metrics と重複しないように注意`

これは重要です。

Spring Boot Actuator / Micrometer でも、HTTP metrics、JVM metrics、HikariCP metrics などを出している場合があります。

その状態で `opentelemetry-javaagent.jar` も同じような metrics を出すと、

```
Micrometer が出す JVM metrics
Java Agent が出す JVM Runtime Metrics
```

のように、似た metrics が二重に出る可能性があります。

そのため、どちらを正式な経路にするか整理が必要です。

---

## 2. `Metrics (業務メソッド)`

対象の行はこれです。

| Signal | 生成元 | アプリ内の主役 | ECS内の経路 | Datadog側の到達先 | Java Agent 構成での注意点 |
| --- | --- | --- | --- | --- | --- |
| Metrics(業務メソッド) | 業務コードから明示的に記録：`BusinessMetricsService`、OTel Metrics API、または Micrometer API | OTel Metrics API を使う場合は Java Agent 内蔵の OpenTelemetry MeterProvider / OTLP metric exporter。Micrometer API を残す場合は Micrometer `MeterRegistry` / Micrometer bridge | app -> OTLP/HTTP `4318` -> Datadog Agent | Datadog Metrics | 注文数、処理成功数、業務エラー数などの業務 metrics は自動では作られない。Micrometer API に統一するなら Micrometer は完全には外さない。OTel Metrics API に移行するなら業務 metrics の実装変更が必要 |

ここは、正確には **`Metrics (業務 metrics)`** と呼んだ方が自然です。

意味としては、**業務メソッドの中で、自分たちが明示的に記録する metrics** です。

---

### `生成元`

```
業務コードから明示的に記録：
BusinessMetricsService、OTel Metrics API、または Micrometer API
```

これは、Java Agent が勝手に作る metrics ではありません。

例えば、以下のようなものです。

```
注文作成数
決済成功数
決済失敗数
在庫引当失敗数
バッチ処理件数
業務エラー数
```

これらは、アプリの業務ロジックを知らないと作れません。

Java Agent は、

```
HTTPリクエストが何回あったか
DBアクセスが何msだったか
JVMメモリをどれだけ使っているか
```

は自動で取れます。

しかし、

```
この処理は「注文成功」なのか
このエラーは「業務エラー」なのか
この件数は「請求対象件数」なのか
```

までは自動では判断できません。

そのため、業務コード側で明示的に記録します。

---

### `BusinessMetricsService`

これは、おそらくプロジェクト内で用意している **業務 metrics を記録するサービスクラス**です。

例えば、イメージとしてはこうです。

```java
businessMetricsService.incrementOrderCreated();
businessMetricsService.incrementPaymentFailed();
businessMetricsService.recordBatchProcessedCount(count);
```

つまり、アプリのあちこちで直接 metrics API を呼ぶのではなく、`BusinessMetricsService` に集約して、

```
業務 metrics の名前
タグ
カウント方法
記録ルール
```

を揃えるためのものです。

---

### `OTel Metrics API`

これは、OpenTelemetry 標準の API を使って業務 metrics を記録する方法です。

イメージとしては、

```
業務コード
  ↓
OTel Metrics API
  ↓
OpenTelemetry MeterProvider
  ↓
OTLP metric exporter
  ↓
Datadog Agent
```

になります。

Micrometer を使わずに OpenTelemetry に寄せたい場合は、この方式になります。

ただし、既存の `BusinessMetricsService` が Micrometer API で実装されているなら、OTel Metrics API へ書き換えが必要です。

---

### `Micrometer API`

これは、Spring Boot でよく使われる metrics API です。

既存の業務 metrics が Micrometer で実装されているなら、たとえば以下のような形になっている可能性があります。

```java
Counter.builder("orders.created")
    .tag("result", "success")
    .register(meterRegistry)
    .increment();
```

この場合、Micrometer を完全に外すと、業務 metrics の記録処理も消える、または動かなくなります。

---

### `アプリ内の主役`

```
OTel Metrics API を使う場合は Java Agent 内蔵の OpenTelemetry MeterProvider / OTLP metric exporter。
Micrometer API を残す場合は Micrometer MeterRegistry / Micrometer bridge
```

ここは、**業務 metrics をどのAPIで出すかによって主役が変わる**という意味です。

### OTel Metrics API を使う場合

```
業務コード
  ↓
OTel Metrics API
  ↓
Java Agent 内蔵の OpenTelemetry MeterProvider
  ↓
OTLP metric exporter
```

この場合は、OpenTelemetry に寄せた構成です。

### Micrometer API を残す場合

```
業務コード
  ↓
Micrometer API
  ↓
Micrometer MeterRegistry
  ↓
Micrometer bridge
  ↓
OpenTelemetry / OTLP
```

`MeterRegistry` は、Micrometer における metrics の登録先です。

`Micrometer bridge` は、Micrometer で作った metrics を OpenTelemetry 側に渡すための橋渡しです。

OpenTelemetry Java Agent の supported libraries では Micrometer も対象として載っていますが、`disabled by default` と記載されています。(OpenTelemetry) そのため、Micrometer API の業務 metrics を Java Agent 経由で扱う場合は、Micrometer bridge を有効化する必要があります。Micrometer bridge 側には OpenTelemetry `MeterRegistry` 実装の設定もあります。(GitHub)

---

### `ECS内の経路`

```
app -> OTLP/HTTP 4318 -> Datadog Agent
```

これは `Metrics (自動計装)` と同じです。

違いは、**metrics の作られ方**です。

```
Metrics (自動計装)
  = Java Agent が自動で作る

Metrics (業務メソッド)
  = 業務コードが明示的に作る
```

送信経路は同じです。

```
Spring Boot アプリコンテナ
  ↓ OTLP/HTTP 4318
Datadog Agent sidecar
  ↓
Datadog Metrics
```

---

### `Datadog側の到達先`

```
Datadog Metrics
```

これも `Metrics (自動計装)` と同じです。

自動計装 metrics も、業務 metrics も、最終的には Datadog Metrics に入ります。

Datadog 側では、例えば以下のように一緒にダッシュボード化できます。

```
注文作成数
決済失敗数
HTTP 5xx 数
DB接続プール使用率
JVM heap使用量
```

---

### `Java Agent 構成での注意点`

### `注文数、処理成功数、業務エラー数などの業務 metrics は自動では作られない`

これは大事です。

`opentelemetry-javaagent.jar` は、アプリの技術的な動きは見られます。

```
HTTP
JDBC
JVM
HikariCP
```

しかし、業務上の意味まではわかりません。

```
注文が成功した
決済が失敗した
在庫引当で業務エラーになった
請求対象件数が100件だった
```

こういう情報は、業務コード側で明示的に記録する必要があります。

---

### `Micrometer API に統一するなら Micrometer は完全には外さない`

既存方針が、

```
業務 metrics は Micrometer API に統一
```

であるなら、Micrometer は残す必要があります。

この場合、Java Agent を使っても、

```
Trace / Span は Java Agent
自動 metrics は Java Agent
業務 metrics は Micrometer API
```

という混在構成になります。

これは変な構成ではありません。むしろ、既存の Spring Boot アプリでは現実的です。

---

### `OTel Metrics API に移行するなら業務 metrics の実装変更が必要`

Micrometer を完全に外したいなら、業務 metrics のコードを OTel Metrics API に書き換える必要があります。

つまり、

```
Micrometer Counter / Timer / Gauge
```

で書いている部分を、

```
OpenTelemetry Metrics API
```

に変える、ということです。

これは設計変更になるので、単に `opentelemetry-javaagent.jar` を追加するだけでは完了しません。

---

## 2行の違いをまとめると

| 観点 | `Metrics (自動計装)` | `Metrics (業務メソッド)` |
| --- | --- | --- |
| 誰が作るか | `opentelemetry-javaagent.jar` が自動で作る | 業務コードが明示的に作る |
| 対象 | HTTP、JDBC、HikariCP、JVM Runtime Metrics など | 注文数、決済失敗数、業務エラー数、バッチ処理件数など |
| 主役 | Java Agent 内蔵の OpenTelemetry MeterProvider / OTLP metric exporter | OTel Metrics API または Micrometer API / MeterRegistry / Micrometer bridge |
| ECS内の経路 | app -> OTLP/HTTP `4318` -> Datadog Agent | app -> OTLP/HTTP `4318` -> Datadog Agent |
| Datadog側の到達先 | Datadog Metrics | Datadog Metrics |
| 注意点 | Micrometer由来のmetricsと重複しないようにする | 業務metricsは自動では作られない。Micrometerを残すか、OTel Metrics APIへ移行するか決める |

---

### 一番わかりやすいイメージ

```
Metrics (自動計装)
  = アプリの技術的な状態を Java Agent が自動で測る

例:
  HTTPリクエスト数
  DBアクセス時間
  HikariCP接続数
  JVMメモリ使用量
```

```
Metrics (業務メソッド)
  = 業務的に意味のある数値を、自分たちのコードで記録する

例:
  注文作成数
  決済失敗数
  在庫引当失敗数
  バッチ処理件数
```

最終的な流れはこうです。

```
自動計装 metrics
  ├─ HTTP metrics
  ├─ JDBC Database Client Metrics
  ├─ HikariCP DB pool metrics
  └─ JVM Runtime Metrics

業務 metrics
  ├─ BusinessMetricsService
  ├─ OTel Metrics API
  └─ Micrometer API

        ↓

OTLP/HTTP 4318

        ↓

Datadog Agent sidecar

        ↓

Datadog Metrics
```

要するに、

```
Metrics (自動計装)
= Java Agent が勝手に集めてくれる技術系の数値

Metrics (業務メソッド)
= 業務コードが明示的に記録するビジネス系の数値
```

という分け方です。

---

### 最低限の設定イメージ

アプリコンテナ側は、だいたいこのような考え方です。

```bash
JAVA_TOOL_OPTIONS="-javaagent:/otel/opentelemetry-javaagent.jar"

OTEL_SERVICE_NAME="your-spring-boot-app"

OTEL_TRACES_EXPORTER="otlp"
OTEL_METRICS_EXPORTER="otlp"
OTEL_LOGS_EXPORTER="none"

# Trace は OTLP/gRPC で Datadog Agent sidecar へ
OTEL_EXPORTER_OTLP_TRACES_PROTOCOL="grpc"
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="http://localhost:4317"

# Metrics は OTLP/HTTP で Datadog Agent sidecar へ
OTEL_EXPORTER_OTLP_METRICS_PROTOCOL="http/protobuf"
OTEL_EXPORTER_OTLP_METRICS_ENDPOINT="http://localhost:4318/v1/metrics"

# JDBC Database Client Metrics を使う場合
OTEL_SEMCONV_STABILITY_OPT_IN="database"
```

Datadog Agent sidecar 側では、OTLP receiver を有効にします。

```bash
DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT="0.0.0.0:4317"
DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT="0.0.0.0:4318"
```

Datadog の OTLP 取り込みはデフォルト無効で、gRPC は `4317`、HTTP は `4318` を使う構成が案内されています。また、コンテナ環境では `0.0.0.0` で待ち受ける設定が示されています。(Datadog 監視)

---

### Micrometer を完全に外せるか

ここが一番重要です。

現行仕様にあるこれです。

```
Metrics | Micrometer MeterRegistry | BusinessMetricsService、Actuator / Micrometer
```

もし `BusinessMetricsService` がこういうことをしているなら、

```java
counter.increment();
timer.record(...);
gauge(...);
```

それは **Micrometer API で作っている業務 metrics** です。

この場合、`opentelemetry-javaagent.jar` だけにすると、Java Agent が自動で取れる JVM / HTTP / JDBC metrics は出ますが、**アプリが独自に作っている業務 metrics は自動では復元されません**。

選択肢は2つです。

```
案A:
  業務 metrics は Micrometer API のまま残す
  Micrometer OTLP registry は外す
  必要なら Java Agent の Micrometer bridge を有効化する

案B:
  業務 metrics を OpenTelemetry Metrics API に書き換える
  Micrometer を外す
```

OpenTelemetry Java Agent の supported libraries では Micrometer は対象ですが、`disabled by default` とされています。(OpenTelemetry) そのため Micrometer の業務 metrics を Java Agent 経由で拾いたい場合は、Micrometer instrumentation を明示的に有効化する構成になります。Java Agent では `otel.instrumentation.[name].enabled` / `OTEL_INSTRUMENTATION_[NAME]_ENABLED` 形式で個別計装を制御できます。(OpenTelemetry)

例：

```bash
OTEL_INSTRUMENTATION_MICROMETER_ENABLED="true"
```

ただし、Micrometer bridge を有効化すると、Micrometer 側の JVM / HTTP metrics と Java Agent 側の JVM / HTTP metrics が重複する可能性があります。業務 metrics だけをきれいに残したい場合は、メトリクス名・タグ・送信経路の整理が必要です。

---

### Trace の生成元はこう変わります

現行：

```
1リクエストの trace
  ├─ HTTPリクエストの span        ← Spring Boot OTel
  ├─ Spring/Micrometer由来の span ← Micrometer Tracing
  └─ Serviceメソッドの span       ← 独自 AOP
```

Java Agent 中心にすると、基本はこうなります。

```
1リクエストの trace
  ├─ HTTP SERVER span             ← opentelemetry-javaagent.jar
  ├─ Controller span              ← Spring Web MVC instrumentation
  ├─ Service method span          ← 必要なら methods instrumentation / @WithSpan / 既存AOP
  └─ JDBC query span              ← JDBC instrumentation
```

ここでのポイントは、**Java Agent が Spring Boot OTel / Micrometer Tracing の代わりに trace/span を作る中心になる**ことです。

ただし、Service の public method span は、Java Agent のデフォルト自動計装だけでは全メソッドに作られるわけではありません。

---

### JDBC / JPA / Hibernate についての注意

`JDBC instrumentation を追加する` という意味では、Java Agent でかなり素直に実現できます。JDBC は OpenTelemetry Java Agent の自動計装対象で、Database Client Spans と Database Client Metrics の対象です。(OpenTelemetry)

ただし、資料上は `JPA / Hibernate / JDBC span` と書かれていても、実際に主に見えるのはこうです。

```
JPA Repository
  ↓
Hibernate
  ↓
JDBC
  ↓
Database

trace上では JDBC query span として見えることが多い
```

OpenTelemetry の supported libraries では Hibernate も載っていますが、機能欄は `none` です。(OpenTelemetry) そのため、**JPA専用spanやHibernate専用spanが必ず細かく出る**とまでは考えず、**JPA/Hibernateの裏側で発行されたSQLがJDBC spanとして見える**、と理解するのが安全です。

SQLの扱いも注意点です。OpenTelemetry Java Agent は DB statement を span 属性に入れる際、文字列や数値などの値を `?` に置き換えるサニタイズをデフォルトで行い、JDBC bind parameters は `db.statement` に含めないと説明されています。(OpenTelemetry) それでも、アプリログ側でSQLパラメータを出している場合は別問題なので、ログにSQLパラメータ値を出さない方針は継続した方がよいです。

---

### 最終的なおすすめ形

今回の要件なら、現実的にはこの形が安全です。

```
Trace / Span:
  opentelemetry-javaagent.jar に寄せる
  JDBC span も Java Agent で追加
  Service public method span が必要なら既存AOPまたは methods instrumentation

Metrics:
  JVM Runtime Metrics は Java Agent
  JDBC metrics は Java Agent
  業務 metrics は Micrometer APIを残すか、OTel Metrics APIへ移行

Logs:
  stdout JSON -> FireLens -> Datadog Logs は維持
  Java Agent は MDC に trace_id / span_id を入れる用途
  OTLP logs は使わない
```

完全に `Micrometer` を消す構成も可能ですが、その場合は **業務 metrics を OpenTelemetry Metrics API に書き換える**必要があります。現行の `BusinessMetricsService` を活かすなら、Micrometer API は残し、`micrometer-registry-otlp` や Micrometer Tracing 系だけを外す、という整理が無難です。