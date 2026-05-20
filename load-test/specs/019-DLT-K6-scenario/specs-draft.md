# specs-draft.md

https://docs.aws.amazon.com/ja_jp/solutions/latest/distributed-load-testing-on-aws/create-test-scenario.html
を参考に、このプロジェクトのTodoアプリケーションのREST APIを呼び出す負荷テストを実施したいです。

- 100名のユーザが複数のREST APIを呼び出すような負荷テストを実施します。
- Test TypeはK6を使用する。
- ユーザ、JWTを100名分作成する。User作成、JWT作成はPython3.13で別々のコードにする。
- テストするAPIの仕様はdocs/backend/api.mdに記載されている。
- これらDLTに関するコードは、`load-test/`に配置します。
- S3にUpするzipファイルは、`load-test/test-case/`の配下に配置します。

- docs/infra/cognito-load-test-user-operations.mdに負荷テスト手順が記載されるが、このドキュメントを要件に従って見直してください。このドキュメントには、User,JWTの作成手順などシナリオをS3にUpすることや、DLTのサイトで行うことなど全て記載してください。
- S3にUpするシナリオなどのZip圧縮の手順なども記載するかスクリプトで対応するならそのスクリプトも作成してください。
- cognito-load-test-user-operations.mdをdocs/load-test/に移動する。
- DLTに合うようなAGENTS.mdを作成してください。
- プロジェクトルートのREADME.mdも修正してください。
