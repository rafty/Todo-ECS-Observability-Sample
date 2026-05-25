# 0. ADR -> specs-draft.md

- `specs/003-OTel-to-backend/`においてSDDでtasks.mdまで対応し、`backend/`のSpring Bootアプリの実装、テストまで実施しました。
- また、`infra/`のAWS CDKプロジェクトに関しては、`specs/004-Datadog-agent-to-cdk/specs-draft.md`に要件のドラフトを作成しました。
- `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`、`docs/adr/adr-0002-trace-correlation-otel-datadog.md`にも関連するADRを記載しました。


ところが、詳細を検討するうえで、不足分などがあったことが判明したので、再度検討し、ADR(specs/003-OTel-to-backend-fix-02/ADR-datadog-tagging-strategy.md)を作成しました。

この内容をもとに、以下を実施してください。

- `docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md`、`docs/adr/adr-0002-trace-correlation-otel-datadog.md`のADRに反映し、修正してください。

- `specs/004-Datadog-agent-to-cdk/specs-draft.md`に反映し、修正してください。

- `specs/003-OTel-to-backend/`で行った実装を修正するために、`specs/003-OTel-to-backend-fix-02/specs-draft.md`に要件ドラフトを作成してください。


# 1. specs-draft.md -> specs.md

`specs/003-OTel-to-backend-fix-02/specs-draft.md`に要件のドラフト版を記載しました。
AWSのプロフェッショナルな視点で、これを分析し、`SddTemplates/spec-template.md` を参考に
要件`specs/003-OTel-to-backend-fix-02/specs.md` を作ってください。


## 1.1. specs-draft.mdの改善: => specs.md
> 期待する要件定義書が作成されない場合

`specs/003-OTel-to-backend-fix-02/specs.md`の結果から、`specs-draft.md`を見直しました。
再度、ファイル`specs/003-OTel-to-backend-fix-02/specs-draft.md`を分析し、
本プロジェクトを検査してください。
AWSのプロフェッショナルな視点で、`specs-draft.md`のドラフト要件を再検討し`specs/003-OTel-to-backend-fix-02/specs.md`を改善してください。


# 2. specs.md -> plan.md

`specs/003-OTel-to-backend-fix-02/specs.md`を分析し、このプロジェクトの改善のための詳細な計画を作成してください。
計画を `SddTemplates/plan-template.md`を参考に,AWSのプロフェッショナルな視点で
`specs/003-OTel-to-backend-fix-02/plan.md`に記述してください。
また、`docs/adr/`のADRも参照するようにしてください。

# 3. Tasks 作成 : tasks.md

`specs/003-OTel-to-backend-fix-02/plan.md` に記載されている計画に従って、詳細な列挙型タスクリストを作成してください。
`SddTemplates/tasks-template.md`を参考に、AWSのプロフェッショナルな視点で、
タスクリストを `specs/003-OTel-to-backend-fix-02/tasks.md` に記述してください。
また、`docs/adr/`のADRも参照するようにしてください。

# 4. タスク実行 : tasks.md

タスクリスト `specs/003-OTel-to-backend-fix-02/tasks.md` を完了してください。
`specs/003-OTel-to-backend-fix-02/spec.md`、
`specs/003-OTel-to-backend-fix-02/plan.md`、
`specs/003-OTel-to-backend-fix-02/tasks.md`、
`docs/adr/`のADR を参照し、
すべてのコンテキストを考慮してタスクリスト内のタスクを実装してください。
タスクを順番に完了することに集中してください。
タスクが完了したら、[x] を使用して完了マークを付けてください。
各ステップが完了したら、タスクリストのマークとタスクの完了マーク [x] を更新することが非常に重要です。

AWSのプロフェッショナルな視点で実装してください。
もし、ローカル環境のJavaのバージョンが古い場合で、ビルドやテストを実行できない場合は、ローカル環境のJavaのバージョンを更新して実装してください。

## 4.1 タスク実行の継続

タスクリスト`specs/003-OTel-to-backend-fix-02/tasks.md`に完了マークがついていないタスクがあります。
続けてタスク実行を実施してください。


# 5. コミットメッセージの作成

`003-OTel-to-backend-fix-02`ブランチで行った変更のコミットメッセージを作成してください。
