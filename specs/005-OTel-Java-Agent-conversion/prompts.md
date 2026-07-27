# 0.1 概要設計(Outline Design) の確認、修正

```
現状、APM 向けの `Trace / Span` および `Metrics` は、主に Micrometer などを使って手動計装しています。

しかし、以下の要件に対応する必要があり、手動計装だけで継続的に対応することが難しくなってきました。

1. `Trace / Span` に `JDBC instrumentation` を追加したい
2. `Metrics` に `JDBC instrumentation` を追加したい
3. `Metrics` に `Runtime Metrics` を追加したい

これらの要件に対応するため、自動計装の導入を検討しました。

候補としては、以下の Java Agent がありました。

* OpenTelemetry Java Agent（`opentelemetry-javaagent.jar`）
* Datadog Java Tracer（`dd-java-agent.jar`）

検討結果は ADR（`docs/adr/adr-0004-OTel-Java-Agent.md`）に記載されており、本プロジェクトでは OpenTelemetry Java Agent を採用して自動計装を行います。

## 依頼内容

OpenTelemetry Java Agent へ対応するための概要設計書として、以下のベース資料を作成済みです。

`specs/005-OTel-Java-Agent-conversion/OTel-Java-Agent-Outline-Design-Base.md`

このベース資料の内容が、本プロジェクトの現行仕様に正しく沿っているかを確認してください。

確認にあたっては、以下を参照してください。

* ADR：`docs/adr/adr-0004-OTel-Java-Agent.md`
* 現行のコード
* 既存のドキュメント
* 既存の設定ファイル
* 現行の APM / OpenTelemetry / Micrometer 関連の実装

## 実施してほしいこと

1. `OTel-Java-Agent-Outline-Design-Base.md` の内容を確認する
2. ADR の決定内容と矛盾していないか確認する
3. 現行コードおよび既存ドキュメントと照らし合わせる
4. 現行仕様と不整合な記述、誤った記述、曖昧な記述があれば修正する
5. OpenTelemetry Java Agent 対応の概要設計書として、SDD で利用できる品質に整理する
6. 修正後の内容を以下のファイルに記載する

`specs/005-OTel-Java-Agent-conversion/OTel-Java-Agent-Outline-Design.md`

## 作成する資料の位置づけ

`OTel-Java-Agent-Outline-Design.md` は、Spec-Driven Development（SDD）で使用する概要設計書です。

そのため、単なるメモではなく、後続の仕様作成や実装方針の検討に使える資料として整理してください。

特に、以下の内容が分かるようにしてください。

* OpenTelemetry Java Agent を導入する背景
* 手動計装から自動計装へ移行する目的
* 対象となる計装範囲

    * `Trace / Span`
    * `Metrics`
    * `JDBC instrumentation`
    * `Runtime Metrics`
* 既存の Micrometer などによる手動計装との関係
* 既存の実装から変更する点
* 既存の実装から削除する点
* OpenTelemetry Java Agent によって自動計装する範囲
* 引き続き手動計装が必要な範囲
* ADR で決定済みの内容
* 設計上の制約
* 実装時の注意点
* 未確定事項や確認が必要な事項

## 注意事項

* ADR の決定内容と矛盾する内容は記載しないでください。
* 現行コードや既存ドキュメントから確認できない内容は、推測で補完しないでください。
* 判断できない内容は、`確認事項` または `TODO` として明記してください。
* `OTel-Java-Agent-Outline-Design-Base.md` の内容をそのままコピーするのではなく、現行仕様に照らして必要な修正を行ってください。
* 既存の設計思想や実装方針を尊重してください。
* 不要な大規模リファクタリングや、依頼範囲外のコード変更は行わないでください。
* 作業後に、主な修正点と、判断できず確認事項として残した点を簡潔にまとめてください。
```

# 0.2 specs-draft.mdの作成

ADR（`docs/adr/adr-0004-OTel-Java-Agent.md`）を参考にOpenTelemetry Java Agentの導入を決めました。
そして OpenTelemetry Java Agentの導入のために、
概要設計書(specs/005-OTel-Java-Agent-conversion/OTel-Java-Agent-Outline-Design.md)を作成しました。
これらのドキュメントと現行プロジェクトのソースコードやドキュメントを元に、Spec-Driven Development(SDD)に使える
specs-draft.md(`specs/005-OTel-Java-Agent-conversion/specs-draft.md`)を作成してください。


# 1. specs-draft.md -> specs.md

`specs/005-OTel-Java-Agent-conversion/specs-draft.md`に要件のドラフト版を記載しました。
AWSのプロフェッショナルな視点で、これを分析し、`SddTemplates/spec-template.md` を参考に
要件`specs/005-OTel-Java-Agent-conversion/specs.md` を作ってください。

また、specs-draft.mdを分析する上で内容や技術に関しておかしな点、間違った点などがあれば、これも`未決事項`等に記載してください。

## 1.1. specs-draft.mdの改善: => specs.md
> 期待する要件定義書が作成されない場合

`specs/005-OTel-Java-Agent-conversion/specs.md`の結果から、`specs-draft.md`を見直しました。
再度、ファイル`specs/005-OTel-Java-Agent-conversion/specs-draft.md`を分析し、
本プロジェクトを検査してください。
AWSのプロフェッショナルな視点で、`specs-draft.md`のドラフト要件を再検討し`specs/005-OTel-Java-Agent-conversion/specs.md`を改善してください。


# 2. specs.md -> plan.md

`specs/005-OTel-Java-Agent-conversion/specs.md`を分析し、このプロジェクトの改善のための詳細な計画を作成してください。
計画を `SddTemplates/plan-template.md`を参考に,AWSのプロフェッショナルな視点で
`specs/005-OTel-Java-Agent-conversion/plan.md`に記述してください。


# 3. Tasks 作成 : tasks.md

`specs/005-OTel-Java-Agent-conversion/plan.md` に記載されている計画に従って、詳細な列挙型タスクリストを作成してください。
`SddTemplates/tasks-template.md`を参考に、AWSのプロフェッショナルな視点で、
タスクリストを `specs/005-OTel-Java-Agent-conversion/tasks.md` に記述してください。


# 4. タスク実行 : tasks.md

タスクリスト `specs/005-OTel-Java-Agent-conversion/tasks.md` を完了してください。
`specs/005-OTel-Java-Agent-conversion/spec.md`、
`specs/005-OTel-Java-Agent-conversion/plan.md`、
`specs/005-OTel-Java-Agent-conversion/tasks.md`、
`docs/adr/`のADR を参照し、
すべてのコンテキストを考慮してタスクリスト内のタスクを実装してください。
spec.md、plan.md、tasks.mdで方針が異なる場合は、spec.md < plan.md < tasks.mdの優先度で、後ろのmdファイルの方針に従ってください。

タスクを順番に完了することに集中してください。
タスクが完了したら、[x] を使用して完了マークを付けてください。
各ステップが完了したら、タスクリストのマークとタスクの完了マーク [x] を更新することが非常に重要です。

AWSのプロフェッショナルな視点で実装してください。

補足：`npx cdk synth`を実行する必要がある場合、テスト使用する環境はprodなので、`npx cdk synth -c env=prod`で実行するようにしてください。

## 4.1 タスク実行の継続

タスクリスト`specs/005-OTel-Java-Agent-conversion/tasks.md`に完了マークがついていないタスクがあります。
引き続きタスク実行を実施してください。


# 5. コミットメッセージの作成

`005-OTel-Java-Agent-conversion`ブランチで行った変更のコミットメッセージを作成してください。
