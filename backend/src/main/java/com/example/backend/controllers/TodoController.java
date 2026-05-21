package com.example.backend.controllers;

import com.example.backend.dto.TodoCreateRequest;
import com.example.backend.dto.TodoListResponse;
import com.example.backend.dto.TodoResponse;
import com.example.backend.dto.TodoUpdateRequest;
import com.example.backend.exception.BadRequestException;
import com.example.backend.logging.OwnerSubjectHashService;
import com.example.backend.model.Todo;
import com.example.backend.services.TodoService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private static final Logger log = LoggerFactory.getLogger(TodoController.class);

    private final TodoService todoService;
    private final OwnerSubjectHashService ownerSubjectHashService;

    public TodoController(TodoService todoService, OwnerSubjectHashService ownerSubjectHashService) {
        this.todoService = todoService;
        this.ownerSubjectHashService = ownerSubjectHashService;
    }

    @GetMapping
    public TodoListResponse listTodos(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "updatedAt,desc") String sort,
            @RequestParam(required = false) Boolean completed,
            @RequestParam(name = "q", required = false) String keyword
    ) {
        // なぜ必要か: 認証主体のsubを所有者識別に固定し、一覧取得でも越境アクセスを防ぐため。
        final String ownerSubject = resolveOwnerSubject(authentication);
        final String ownerSubjectHash = ownerSubjectHashService.hash(ownerSubject);
        final Page<Todo> todoPage = todoService.listTodos(ownerSubject, completed, keyword, page, size, sort);

        // なぜ必要か: 一覧取得の結果件数と検索条件を業務ログとして残し、障害時の取得挙動を再現しやすくするため。
        log.atInfo()
                .setMessage("Todo list retrieved")
                .addKeyValue("eventType", "BUSINESS")
                .addKeyValue("action", "LIST")
                .addKeyValue("httpStatus", 200)
                .addKeyValue("ownerSubjectHash", ownerSubjectHash)
                .addKeyValue("resultCount", todoPage.getNumberOfElements())
                .addKeyValue("page", todoPage.getNumber())
                .addKeyValue("size", todoPage.getSize())
                .log();

        return new TodoListResponse(
                todoPage.getContent().stream().map(TodoResponse::from).toList(),
                todoPage.getNumber(),
                todoPage.getSize(),
                todoPage.getTotalElements(),
                todoPage.getTotalPages(),
                sort
        );
    }

    @GetMapping("/{todoId}")
    public TodoResponse getTodo(Authentication authentication, @PathVariable Long todoId) {
        // なぜ必要か: 単票取得でも所有者境界を適用し、存在有無の情報漏えいを防ぐため。
        final String ownerSubject = resolveOwnerSubject(authentication);
        final String ownerSubjectHash = ownerSubjectHashService.hash(ownerSubject);
        final Todo todo = todoService.getTodo(ownerSubject, todoId);

        // なぜ必要か: 単票取得結果を業務ログで追跡し、対象ID単位の利用状況を把握できるようにするため。
        log.atInfo()
                .setMessage("Todo retrieved")
                .addKeyValue("eventType", "BUSINESS")
                .addKeyValue("action", "GET")
                .addKeyValue("httpStatus", 200)
                .addKeyValue("ownerSubjectHash", ownerSubjectHash)
                .addKeyValue("todoId", todoId)
                .log();

        return TodoResponse.from(todo);
    }

    @PostMapping
    public ResponseEntity<TodoResponse> createTodo(
            Authentication authentication,
            @Valid @RequestBody TodoCreateRequest request
    ) {
        // なぜ必要か: 作成時のowner_subjectをJWT由来で確定し、クライアント入力での偽装を排除するため。
        final String ownerSubject = resolveOwnerSubject(authentication);
        final String ownerSubjectHash = ownerSubjectHashService.hash(ownerSubject);
        final Todo createdTodo = todoService.createTodo(
                ownerSubject,
                request.title(),
                request.description(),
                request.completed()
        );
        final URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{todoId}")
                .buildAndExpand(createdTodo.getId())
                .toUri();

        // なぜ必要か: 書き込み操作の主体・対象・結果を監査ログとして残し、監査証跡を確保するため。
        log.atInfo()
                .setMessage("Todo created")
                .addKeyValue("eventType", "AUDIT")
                .addKeyValue("action", "CREATE")
                .addKeyValue("httpStatus", 201)
                .addKeyValue("ownerSubjectHash", ownerSubjectHash)
                .addKeyValue("todoId", createdTodo.getId())
                .addKeyValue("result", "SUCCESS")
                .log();

        return ResponseEntity.created(location).body(TodoResponse.from(createdTodo));
    }

    @PutMapping("/{todoId}")
    public TodoResponse updateTodo(
            Authentication authentication,
            @PathVariable Long todoId,
            @Valid @RequestBody TodoUpdateRequest request
    ) {
        // なぜ必要か: 更新時もJWT `sub` を所有者条件として適用し、他ユーザーTodo更新を防ぐため。
        final String ownerSubject = resolveOwnerSubject(authentication);
        final String ownerSubjectHash = ownerSubjectHashService.hash(ownerSubject);
        final Todo updatedTodo = todoService.updateTodo(
                ownerSubject, todoId, request.title(), request.description(), request.completed()
        );

        // なぜ必要か: 更新操作の主体・対象・結果を監査ログへ記録し、改変履歴を追跡できるようにするため。
        log.atInfo()
                .setMessage("Todo updated")
                .addKeyValue("eventType", "AUDIT")
                .addKeyValue("action", "UPDATE")
                .addKeyValue("httpStatus", 200)
                .addKeyValue("ownerSubjectHash", ownerSubjectHash)
                .addKeyValue("todoId", todoId)
                .addKeyValue("result", "SUCCESS")
                .log();

        return TodoResponse.from(updatedTodo);
    }

    @DeleteMapping("/{todoId}")
    public ResponseEntity<Void> deleteTodo(Authentication authentication, @PathVariable Long todoId) {
        // なぜ必要か: 削除APIでも所有者境界を維持し、許可されない削除を404に統一するため。
        final String ownerSubject = resolveOwnerSubject(authentication);
        final String ownerSubjectHash = ownerSubjectHashService.hash(ownerSubject);
        todoService.deleteTodo(ownerSubject, todoId);

        // なぜ必要か: 削除操作の実行結果を監査ログとして残し、証跡確認と事後調査を可能にするため。
        log.atInfo()
                .setMessage("Todo deleted")
                .addKeyValue("eventType", "AUDIT")
                .addKeyValue("action", "DELETE")
                .addKeyValue("httpStatus", 204)
                .addKeyValue("ownerSubjectHash", ownerSubjectHash)
                .addKeyValue("todoId", todoId)
                .addKeyValue("result", "SUCCESS")
                .log();

        return ResponseEntity.noContent().build();
    }

    private String resolveOwnerSubject(Authentication authentication) {
        // なぜ必要か: 認証コンテキストの識別子欠落を検知し、owner判定が空値になる事故を防ぐため。
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new BadRequestException("Authenticated subject is missing");
        }
        return authentication.getName();
    }
}
