# 1. specs-draft.md -> specs.md

`specs/004-Datadog-agent-to-cdk/specs-draft.md`に要件のドラフト版を記載しました。
AWSのプロフェッショナルな視点で、これを分析し、`SddTemplates/spec-template.md` を参考に
要件`specs/004-Datadog-agent-to-cdk/specs.md` を作ってください。

また、specs-draft.mdを分析する上で内容や技術に関しておかしな点、間違った点などがあれば、これも`未決事項`等に記載してください。

## 1.1. specs-draft.mdの改善: => specs.md
> 期待する要件定義書が作成されない場合

`specs/004-Datadog-agent-to-cdk/specs.md`の結果から、`specs-draft.md`を見直しました。
再度、ファイル`specs/004-Datadog-agent-to-cdk/specs-draft.md`を分析し、
本プロジェクトを検査してください。
AWSのプロフェッショナルな視点で、`specs-draft.md`のドラフト要件を再検討し`specs/004-Datadog-agent-to-cdk/specs.md`を改善してください。


# 2. specs.md -> plan.md

`specs/004-Datadog-agent-to-cdk/specs.md`を分析し、このプロジェクトの改善のための詳細な計画を作成してください。
計画を `SddTemplates/plan-template.md`を参考に,AWSのプロフェッショナルな視点で
`specs/004-Datadog-agent-to-cdk/plan.md`に記述してください。


# 3. Tasks 作成 : tasks.md

`specs/004-Datadog-agent-to-cdk/plan.md` に記載されている計画に従って、詳細な列挙型タスクリストを作成してください。
`SddTemplates/tasks-template.md`を参考に、AWSのプロフェッショナルな視点で、
タスクリストを `specs/004-Datadog-agent-to-cdk/tasks.md` に記述してください。


# 4. タスク実行 : tasks.md

タスクリスト `specs/004-Datadog-agent-to-cdk/tasks.md` を完了してください。
`specs/004-Datadog-agent-to-cdk/spec.md`、
`specs/004-Datadog-agent-to-cdk/plan.md`、
`specs/004-Datadog-agent-to-cdk/tasks.md`、
`docs/adr/`のADR を参照し、
すべてのコンテキストを考慮してタスクリスト内のタスクを実装してください。
タスクを順番に完了することに集中してください。
タスクが完了したら、[x] を使用して完了マークを付けてください。
各ステップが完了したら、タスクリストのマークとタスクの完了マーク [x] を更新することが非常に重要です。

AWSのプロフェッショナルな視点で実装してください。

補足：`npx cdk synth`を実行する必要がある場合、テスト使用する環境はprodなので、`npx cdk synth -c env=prod`で実行するようにしてください。

## 4.1 タスク実行の継続

タスクリスト`specs/004-Datadog-agent-to-cdk/tasks.md`に完了マークがついていないタスクがあります。
続けてタスク実行を実施してください。


# 5. コミットメッセージの作成

`004-Datadog-agent-to-cdk`ブランチで行った変更のコミットメッセージを作成してください。
