package com.example.backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(TodoNotFoundException.class)
    public ProblemDetail handleTodoNotFound(TodoNotFoundException exception, HttpServletRequest request) {
        // なぜ必要か: 権限不整合と未存在を404へ統一し、リソース存在有無の推測を防ぐため。
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Todo not found");
        problemDetail.setTitle("Not Found");
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setInstance(URI.create(request.getRequestURI()));

        // なぜ必要か: 想定内の未存在系エラーをWARNで残し、エラー原因と対象パスを運用で追跡できるようにするため。
        log.atWarn()
                .setMessage("Todo not found")
                .addKeyValue("eventType", "ERROR")
                .addKeyValue("action", request.getMethod())
                .addKeyValue("httpStatus", 404)
                .addKeyValue("path", request.getRequestURI())
                .log();

        return problemDetail;
    }

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handleBadRequest(BadRequestException exception, HttpServletRequest request) {
        // なぜ必要か: パラメータ違反を400として明示し、クライアント側で修正可能なエラーを区別しやすくするため。
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problemDetail.setTitle("Bad Request");
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setInstance(URI.create(request.getRequestURI()));

        // なぜ必要か: クライアント修正可能な入力エラーをWARNで記録し、異常傾向を把握可能にするため。
        log.atWarn()
                .setMessage("Bad request detected")
                .addKeyValue("eventType", "ERROR")
                .addKeyValue("action", request.getMethod())
                .addKeyValue("httpStatus", 400)
                .addKeyValue("path", request.getRequestURI())
                .log();

        return problemDetail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationError(MethodArgumentNotValidException exception, HttpServletRequest request) {
        // なぜ必要か: バリデーション失敗の詳細をProblem Detailsへ統一し、クライアント実装の再現性を高めるため。
        final List<Map<String, String>> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toFieldErrorEntry)
                .toList();

        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Validation failed"
        );
        problemDetail.setTitle("Bad Request");
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("errors", fieldErrors);

        // なぜ必要か: バリデーション失敗の件数をWARNで記録し、入力不備の増加を運用で検知しやすくするため。
        log.atWarn()
                .setMessage("Validation failed")
                .addKeyValue("eventType", "ERROR")
                .addKeyValue("action", request.getMethod())
                .addKeyValue("httpStatus", 400)
                .addKeyValue("path", request.getRequestURI())
                .addKeyValue("errorCount", fieldErrors.size())
                .log();

        return problemDetail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnhandledException(Exception exception, HttpServletRequest request) {
        // なぜ必要か: 予期しない例外を500のProblem Detailsへ統一し、API契約と障害解析の再現性を担保するため。
        final ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error"
        );
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setInstance(URI.create(request.getRequestURI()));

        // なぜ必要か: 想定外障害の原因調査に必要なHTTP文脈をERRORで記録し、追跡時間を短縮するため。
        log.atError()
                .setMessage("Unhandled exception during request processing")
                .addKeyValue("eventType", "ERROR")
                .addKeyValue("action", "UNHANDLED_EXCEPTION")
                .addKeyValue("httpStatus", 500)
                .addKeyValue("path", request.getRequestURI())
                .setCause(exception)
                .log();

        return problemDetail;
    }

    private Map<String, String> toFieldErrorEntry(FieldError fieldError) {
        // なぜ必要か: フィールド名とエラー内容を固定キーで返し、クライアント側のエラー表示処理を単純化するため。
        return Map.of(
                "field", fieldError.getField(),
                "message", fieldError.getDefaultMessage() == null ? "Invalid value" : fieldError.getDefaultMessage()
        );
    }
}
