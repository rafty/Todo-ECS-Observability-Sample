# 1. adrを完成させる

# 前提
- `specs/002-OTel-ADR/0001-o11y-datadog-otel-ecs-fargate.md`は、Observabilityを実現するためのADRのサンプルです。
- `SddTemplates/adr-template.md`のテンプレートに従っています。
- まだまだ、内容に不備があるので、本要件でブラッシュアップしていきます。
- ADRが完成したら、`docs/adr`に完成版を作成します。

# 要望
- `specs/002-OTel-ADR/0001-o11y-datadog-otel-ecs-fargate.md`の内容を確認し、ADRとして修正すべきところがないかを確認し、修正案を提示してください。

# 2. specs-draft.mdのレビューと分割可能か？

`specs/002-OTel-ADR/specs-draft.md`は、
古いADR(specs/002-OTel-ADR/0001-o11y-datadog-otel-ecs-fargate.md)を参考に作成したspecs.mdのドラフトです。
これを、新しいADR(docs/adr/adr-0001-o11y-datadog-otel-ecs-fargate.md)を元におかしな点がないか見直してください。

# 3. specs-draft.mdの分割

`specs/002-OTel-ADR/specs-draft-changed.md`の要件ドラフトは、
1. `backend/`のSpring Bootに対する実装
2. `infra/`のAWS CDKに対する実装
の２つに大きく分かれると思います。

２つの要件(specs.md)に分割して実装したいのですが、可能でしょうか？

可能でしたら、
specs/002-OTel-ADR/specs-draft-1.mdと
specs/002-OTel-ADR/specs-draft-2.mdに
分割してください。
