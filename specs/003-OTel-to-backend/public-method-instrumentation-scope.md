# Public Method Instrumentation Scope: 003-OTel-to-backend

## 対象（span 付与）

以下は Spring 管理 Bean の `public` メソッドとして、Tracer API ベースの span 付与対象とする。

- `controllers/TodoController`
  - `listTodos`
  - `getTodo`
  - `createTodo`
  - `updateTodo`
  - `deleteTodo`
- `services/impl/TodoServiceImpl`
  - `listTodos`
  - `getTodo`
  - `createTodo`
  - `updateTodo`
  - `deleteTodo`
- `logging/OwnerSubjectHashService`
  - `hash`
- `security/AccessTokenClaimValidator`
  - `validate`
- `exception/ApiExceptionHandler`
  - `handleTodoNotFound`
  - `handleBadRequest`
  - `handleValidationError`
  - `handleUnhandledException`

## 対象外（span 付与しない）

- `private` / `protected` メソッド
  - 例: `RequestLoggingContextFilter#doFilterInternal`
- DTO / Entity の accessor・factory
  - `dto/*`
  - `model/Todo` の getter/setter
- Repository と Specification
  - `repository/*`（Spring Data JPA の自動計装を優先）
  - `repository/specification/*` の static メソッド
- 起動エントリポイント
  - `BackendApplication#main`
- Spring 管理外の public メソッド
  - 例: 直接 `new` されるだけで Bean 管理されないクラスのメソッド

## 補足

- self-invocation は Spring AOP の適用境界外となるため、必要時は明示的 Tracer API 呼び出しで補完する。
- span 名は `todo.*` を中心とし、非業務メソッドは低カーディナリティ名に集約する。
