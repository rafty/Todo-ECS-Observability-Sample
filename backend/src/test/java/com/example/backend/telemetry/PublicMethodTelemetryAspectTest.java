package com.example.backend.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// なぜ必要か: 全対象publicメソッドに対してspan名解決と生成が行われることを固定し、計装漏れ回帰を防ぐため。
@ExtendWith(MockitoExtension.class)
class PublicMethodTelemetryAspectTest {

    @Mock
    private OpenTelemetry openTelemetry;

    @Mock
    private Tracer tracer;

    @Mock
    private SpanBuilder spanBuilder;

    @Mock
    private Span span;

    @Mock
    private Scope scope;

    @Mock
    private SpanContext spanContext;

    @Mock
    private BusinessMetricsService businessMetricsService;

    private PublicMethodTelemetryAspect aspect;

    @BeforeEach
    void setUp() {
        // なぜ必要か: アスペクト実行時のOTel依存をモックへ固定し、span名検証に集中できるようにするため。
        when(openTelemetry.getTracer("com.example.backend.public-method")).thenReturn(tracer);
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(span.makeCurrent()).thenReturn(scope);
        when(span.getSpanContext()).thenReturn(spanContext);
        when(spanContext.isValid()).thenReturn(false);

        aspect = new PublicMethodTelemetryAspect(openTelemetry, businessMetricsService);
    }

    @Test
    void shouldCreateSpanForAllTargetPublicMethods() throws Throwable {
        // なぜ必要か: specsで対象化したpublicメソッド群を1件ずつ通し、span生成の網羅性を担保するため。
        invoke(TodoController.class, "listTodos");
        invoke(TodoController.class, "getTodo");
        invoke(TodoController.class, "createTodo");
        invoke(TodoController.class, "updateTodo");
        invoke(TodoController.class, "deleteTodo");

        invoke(TodoServiceImpl.class, "listTodos");
        invoke(TodoServiceImpl.class, "getTodo");
        invoke(TodoServiceImpl.class, "createTodo");
        invoke(TodoServiceImpl.class, "updateTodo");
        invoke(TodoServiceImpl.class, "deleteTodo");

        invoke(OwnerSubjectHashService.class, "hash");
        invoke(AccessTokenClaimValidator.class, "validate");

        invoke(ApiExceptionHandler.class, "handleTodoNotFound");
        invoke(ApiExceptionHandler.class, "handleBadRequest");
        invoke(ApiExceptionHandler.class, "handleValidationError");
        invoke(ApiExceptionHandler.class, "handleUnhandledException");

        // なぜ必要か: 全呼び出し分のspan名を検証し、operation解決規則の崩れを検知できるようにするため。
        final ArgumentCaptor<String> spanNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(tracer, times(16)).spanBuilder(spanNameCaptor.capture());
        assertThat(spanNameCaptor.getAllValues())
                .containsExactly(
                        "todo.list",
                        "todo.get",
                        "todo.create",
                        "todo.update",
                        "todo.delete",
                        "todo.service.list",
                        "todo.service.get",
                        "todo.service.create",
                        "todo.service.update",
                        "todo.service.delete",
                        "logging.owner.hash",
                        "security.token.validate",
                        "api.exception.handle",
                        "api.exception.handle",
                        "api.exception.handle",
                        "api.exception.handle"
                );

        // なぜ必要か: span開始・終了が各対象メソッドで実行されることを確認し、未終了spanによる運用劣化を防ぐため。
        verify(spanBuilder, times(16)).startSpan();
        verify(span, times(16)).end();
    }

    private void invoke(Class<?> declaringType, String methodName) throws Throwable {
        // なぜ必要か: AOP JoinPointを最小限モックし、対象メソッドごとのoperation解決を再現するため。
        final ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        final MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn(declaringType);
        when(signature.getMethod()).thenReturn(declaringType.getMethod(methodName));
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.tracePublicMethod(joinPoint);
    }

    static class TodoController {
        public void listTodos() {
        }

        public void getTodo() {
        }

        public void createTodo() {
        }

        public void updateTodo() {
        }

        public void deleteTodo() {
        }
    }

    static class TodoServiceImpl {
        public void listTodos() {
        }

        public void getTodo() {
        }

        public void createTodo() {
        }

        public void updateTodo() {
        }

        public void deleteTodo() {
        }
    }

    static class OwnerSubjectHashService {
        public void hash() {
        }
    }

    static class AccessTokenClaimValidator {
        public void validate() {
        }
    }

    static class ApiExceptionHandler {
        public void handleTodoNotFound() {
        }

        public void handleBadRequest() {
        }

        public void handleValidationError() {
        }

        public void handleUnhandledException() {
        }
    }
}
