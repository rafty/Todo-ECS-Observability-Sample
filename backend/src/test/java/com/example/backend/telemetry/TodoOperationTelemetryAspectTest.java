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
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

// なぜ必要か: Java Agent導入後もTodo業務operationに限定したspanとmetricsが失われないことを固定するため。
@ExtendWith(MockitoExtension.class)
class TodoOperationTelemetryAspectTest {

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

    private TodoOperationTelemetryAspect aspect;

    @BeforeEach
    void setUp() {
        // なぜ必要か: OTel依存をモックへ固定し、業務operation名とMDC復元の検証に集中するため。
        when(openTelemetry.getTracer("com.example.backend.todo-operation")).thenReturn(tracer);
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(span.makeCurrent()).thenReturn(scope);
        when(span.getSpanContext()).thenReturn(spanContext);
        when(spanContext.isValid()).thenReturn(true);
        lenient().when(spanContext.getTraceId()).thenReturn("0123456789abcdef0123456789abcdef");
        lenient().when(spanContext.getSpanId()).thenReturn("0123456789abcdef");

        final TodoOperationSpanService spanService = new TodoOperationSpanService(openTelemetry);
        aspect = new TodoOperationTelemetryAspect(spanService, businessMetricsService);
    }

    @Test
    void shouldCreateSpanOnlyForTodoBusinessOperations() throws Throwable {
        // なぜ必要か: 対象operationをTodo CRUDの低カーディナリティ値へ固定し、全public method spanへの回帰を防ぐため。
        invoke("listTodos");
        invoke("getTodo");
        invoke("createTodo");
        invoke("updateTodo");
        invoke("deleteTodo");

        final ArgumentCaptor<String> spanNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(tracer, times(5)).spanBuilder(spanNameCaptor.capture());
        assertThat(spanNameCaptor.getAllValues())
                .containsExactly("todo.list", "todo.get", "todo.create", "todo.update", "todo.delete");
        verify(spanBuilder, times(5)).startSpan();
        verify(span, times(5)).end();
    }

    @Test
    void shouldRecordSuccessMetricAndRestoreMdc() throws Throwable {
        MDC.put("trace_id", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        MDC.put("span_id", "bbbbbbbbbbbbbbbb");

        invoke("createTodo");

        // なぜ必要か: 業務metricsがAOP限定化後も成功operationとして記録されることを担保するため。
        verify(businessMetricsService).recordSuccess(org.mockito.Mockito.eq("todo.create"), org.mockito.Mockito.anyDouble());
        assertThat(MDC.get("trace_id")).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(MDC.get("span_id")).isEqualTo("bbbbbbbbbbbbbbbb");

        MDC.clear();
    }

    @Test
    void shouldRecordFailureMetricAndRethrow() throws Throwable {
        final ProceedingJoinPoint joinPoint = joinPoint("updateTodo");
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("forced"));

        assertThatThrownBy(() -> aspect.traceTodoOperation(joinPoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced");

        // なぜ必要か: 例外発生時も失敗metricsとspan終了が実行されることを保証するため。
        verify(businessMetricsService).recordFailure(org.mockito.Mockito.eq("todo.update"), org.mockito.Mockito.anyDouble());
        verify(span).end();
    }

    @Test
    void shouldNotOverwriteMdcWhenSpanContextIsInvalid() throws Throwable {
        when(spanContext.isValid()).thenReturn(false);
        MDC.put("trace_id", "cccccccccccccccccccccccccccccccc");
        MDC.put("span_id", "dddddddddddddddd");

        invoke("deleteTodo");

        // なぜ必要か: 無効なSpanContextから相関IDを作らず、外側の相関文脈を壊さないことを担保するため。
        assertThat(MDC.get("trace_id")).isEqualTo("cccccccccccccccccccccccccccccccc");
        assertThat(MDC.get("span_id")).isEqualTo("dddddddddddddddd");

        MDC.clear();
    }

    private void invoke(String methodName) throws Throwable {
        final ProceedingJoinPoint joinPoint = joinPoint(methodName);
        when(joinPoint.proceed()).thenReturn("ok");
        aspect.traceTodoOperation(joinPoint);
    }

    private ProceedingJoinPoint joinPoint(String methodName) throws NoSuchMethodException {
        // なぜ必要か: AOP JoinPointを最小限モックし、TodoServiceImplのメソッド名からoperation解決を再現するため。
        final ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        final MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(TodoServiceImpl.class.getMethod(methodName));
        return joinPoint;
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
}
