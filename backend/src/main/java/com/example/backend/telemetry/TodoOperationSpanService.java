package com.example.backend.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class TodoOperationSpanService {

    private static final String TRACE_ID_KEY = "trace_id";
    private static final String SPAN_ID_KEY = "span_id";
    private static final String OPERATION_ATTRIBUTE_KEY = "business.operation";
    private static final String RESULT_ATTRIBUTE_KEY = "result.status";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILURE = "failure";

    private final Tracer tracer;

    public TodoOperationSpanService(OpenTelemetry openTelemetry) {
        // なぜ必要か: Todo業務操作に限定した手動spanであることをTracer名から判別できるようにするため。
        this.tracer = openTelemetry.getTracer("com.example.backend.todo-operation");
    }

    public Object trace(String operation, ThrowingOperation operationBody) throws Throwable {
        // なぜ必要か: Java Agentが自動生成しない業務操作単位のspanだけを専用責務として扱うため。
        final Span span = tracer.spanBuilder(operation).startSpan();
        final String previousTraceId = MDC.get(TRACE_ID_KEY);
        final String previousSpanId = MDC.get(SPAN_ID_KEY);

        try (Scope ignored = span.makeCurrent()) {
            // なぜ必要か: Java AgentのMDC注入が利用できない局面でも、有効なOTel SpanContextがあれば相関キーを維持するため。
            applyTraceMdcIfValid(span.getSpanContext());
            span.setAttribute(OPERATION_ATTRIBUTE_KEY, operation);

            final Object result = operationBody.proceed();

            // なぜ必要か: 業務操作結果をspan属性でも低カーディナリティに固定するため。
            span.setStatus(StatusCode.OK);
            span.setAttribute(RESULT_ATTRIBUTE_KEY, RESULT_SUCCESS);
            return result;
        } catch (Throwable throwable) {
            // なぜ必要か: 例外発生時にAPM上で業務operation単位の失敗原因を追跡できるようにするため。
            span.recordException(throwable);
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(RESULT_ATTRIBUTE_KEY, RESULT_FAILURE);
            throw throwable;
        } finally {
            // なぜ必要か: スレッド再利用時に内側の業務span IDが別処理のログへ漏れないようにするため。
            restoreMdc(previousTraceId, previousSpanId);
            span.end();
        }
    }

    private void applyTraceMdcIfValid(SpanContext spanContext) {
        // なぜ必要か: 無効なSpanContextから相関IDを生成せず、OpenTelemetry由来値だけをログ相関キーへ採用するため。
        if (!spanContext.isValid()) {
            return;
        }
        MDC.put(TRACE_ID_KEY, spanContext.getTraceId().toLowerCase(Locale.ROOT));
        MDC.put(SPAN_ID_KEY, spanContext.getSpanId().toLowerCase(Locale.ROOT));
    }

    private void restoreMdc(String previousTraceId, String previousSpanId) {
        // なぜ必要か: 外側のJava Agent自動spanが設定したMDCを壊さず、業務spanの局所的な上書きだけを戻すため。
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

    @FunctionalInterface
    public interface ThrowingOperation {
        Object proceed() throws Throwable;
    }
}
