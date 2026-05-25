package com.example.backend.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Aspect
@Component
public class PublicMethodTelemetryAspect {

    private static final String TRACE_ID_KEY = "trace_id";
    private static final String SPAN_ID_KEY = "span_id";
    private static final String OPERATION_ATTRIBUTE_KEY = "business.operation";
    private static final String RESULT_ATTRIBUTE_KEY = "result.status";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILURE = "failure";

    private final Tracer tracer;
    private final BusinessMetricsService businessMetricsService;

    public PublicMethodTelemetryAspect(OpenTelemetry openTelemetry, BusinessMetricsService businessMetricsService) {
        // なぜ必要か: 監視基盤で識別しやすいTracer名を固定し、計装ソースを明確化するため。
        this.tracer = openTelemetry.getTracer("com.example.backend.public-method");
        this.businessMetricsService = businessMetricsService;
    }

    @Around(
            "execution(public * com.example.backend..*(..))"
                    + " && !within(com.example.backend.telemetry..*)"
                    + " && !within(com.example.backend.dto..*)"
                    + " && !within(com.example.backend.model..*)"
                    + " && !within(com.example.backend.repository..*)"
                    + " && !within(com.example.backend.BackendApplication)"
                    + " && !execution(public static void *.main(..))"
    )
    public Object tracePublicMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        // なぜ必要か: publicメソッド単位で統一的にspanを作成し、実装漏れを抑止するため。
        final String operation = resolveOperation(joinPoint);
        final String spanName = resolveSpanName(operation);
        final Span span = tracer.spanBuilder(spanName).startSpan();
        final long startedAt = System.nanoTime();
        final String previousTraceId = MDC.get(TRACE_ID_KEY);
        final String previousSpanId = MDC.get(SPAN_ID_KEY);

        try (Scope ignored = span.makeCurrent()) {
            // なぜ必要か: ログとAPM相関の主キーをMDCへ投入し、JSONログへ確実に反映するため。
            applyTraceMdc(span.getSpanContext());
            span.setAttribute(OPERATION_ATTRIBUTE_KEY, operation);

            final Object result = joinPoint.proceed();

            // なぜ必要か: 成功終了をspan属性へ明示し、Datadog上で成功/失敗集計しやすくするため。
            span.setStatus(StatusCode.OK);
            span.setAttribute(RESULT_ATTRIBUTE_KEY, RESULT_SUCCESS);
            businessMetricsService.recordSuccess(operation, elapsedMillis(startedAt));
            return result;
        } catch (Throwable throwable) {
            // なぜ必要か: 例外種別と失敗状態をspanへ記録し、障害調査時の原因特定を容易にするため。
            span.recordException(throwable);
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(RESULT_ATTRIBUTE_KEY, RESULT_FAILURE);
            businessMetricsService.recordFailure(operation, elapsedMillis(startedAt));
            throw throwable;
        } finally {
            // なぜ必要か: ネスト呼び出し後にMDCを復元し、別処理へ文脈が漏れないようにするため。
            restoreMdc(previousTraceId, previousSpanId);
            span.end();
        }
    }

    private void applyTraceMdc(SpanContext spanContext) {
        // なぜ必要か: OpenTelemetryの有効なSpanContextのみ相関キーとして採用し、不正値混入を防ぐため。
        if (!spanContext.isValid()) {
            MDC.remove(TRACE_ID_KEY);
            MDC.remove(SPAN_ID_KEY);
            return;
        }
        MDC.put(TRACE_ID_KEY, spanContext.getTraceId().toLowerCase(Locale.ROOT));
        MDC.put(SPAN_ID_KEY, spanContext.getSpanId().toLowerCase(Locale.ROOT));
    }

    private void restoreMdc(String previousTraceId, String previousSpanId) {
        // なぜ必要か: 呼び出し前のMDC状態へ戻し、外側spanの相関キーを壊さないようにするため。
        if (previousTraceId == null) {
            MDC.remove(TRACE_ID_KEY);
        } else {
            MDC.put(TRACE_ID_KEY, previousTraceId);
        }
        if (previousSpanId == null) {
            MDC.remove(SPAN_ID_KEY);
        } else {
            MDC.put(SPAN_ID_KEY, previousSpanId);
        }
    }

    private String resolveOperation(ProceedingJoinPoint joinPoint) {
        // なぜ必要か: メトリクス属性のoperationを低カーディナリティで固定し、時系列爆発を抑えるため。
        final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        final String className = signature.getDeclaringType().getSimpleName();
        final String methodName = signature.getMethod().getName();

        if ("TodoController".equals(className)) {
            return switch (methodName) {
                case "listTodos" -> "todo.list";
                case "getTodo" -> "todo.get";
                case "createTodo" -> "todo.create";
                case "updateTodo" -> "todo.update";
                case "deleteTodo" -> "todo.delete";
                default -> "todo.controller.other";
            };
        }

        if ("TodoServiceImpl".equals(className)) {
            return switch (methodName) {
                case "listTodos" -> "todo.service.list";
                case "getTodo" -> "todo.service.get";
                case "createTodo" -> "todo.service.create";
                case "updateTodo" -> "todo.service.update";
                case "deleteTodo" -> "todo.service.delete";
                default -> "todo.service.other";
            };
        }

        if ("RequestLoggingContextFilter".equals(className)) {
            return "logging.request.context";
        }

        if ("ApiExceptionHandler".equals(className)) {
            return "api.exception.handle";
        }

        if ("AccessTokenClaimValidator".equals(className)) {
            return "security.token.validate";
        }

        if ("SecurityConfig".equals(className)) {
            return "security.config";
        }

        if ("OwnerSubjectHashService".equals(className)) {
            return "logging.owner.hash";
        }

        return "backend.other.public";
    }

    private String resolveSpanName(String operation) {
        // なぜ必要か: span名をメトリクスoperationと揃え、Datadog上の検索軸を単純化するため。
        return operation;
    }

    private double elapsedMillis(long startedAt) {
        // なぜ必要か: メトリクスの単位(ms)へ正規化し、閾値運用と可視化を統一するため。
        return (System.nanoTime() - startedAt) / 1_000_000d;
    }
}
