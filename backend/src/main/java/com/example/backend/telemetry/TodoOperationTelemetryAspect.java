package com.example.backend.telemetry;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TodoOperationTelemetryAspect {

    private final TodoOperationSpanService spanService;
    private final BusinessMetricsService businessMetricsService;

    public TodoOperationTelemetryAspect(TodoOperationSpanService spanService, BusinessMetricsService businessMetricsService) {
        this.spanService = spanService;
        this.businessMetricsService = businessMetricsService;
    }

    @Around(
            "execution(public * com.example.backend.services.impl.TodoServiceImpl.listTodos(..))"
                    + " || execution(public * com.example.backend.services.impl.TodoServiceImpl.getTodo(..))"
                    + " || execution(public * com.example.backend.services.impl.TodoServiceImpl.createTodo(..))"
                    + " || execution(public * com.example.backend.services.impl.TodoServiceImpl.updateTodo(..))"
                    + " || execution(public * com.example.backend.services.impl.TodoServiceImpl.deleteTodo(..))"
    )
    public Object traceTodoOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        // なぜ必要か: Java Agentが自動生成しない業務operationだけをspan/metrics対象にし、全public method計装への回帰を防ぐため。
        final String operation = resolveOperation(joinPoint);
        final long startedAt = System.nanoTime();

        try {
            final Object result = spanService.trace(operation, joinPoint::proceed);
            // なぜ必要か: 業務操作の成功件数と処理時間をspanとは別責務のMicrometer metricsとして維持するため。
            businessMetricsService.recordSuccess(operation, elapsedMillis(startedAt));
            return result;
        } catch (Throwable throwable) {
            // なぜ必要か: 例外発生時も失敗metricsを残し、APM spanと同じoperation軸で調査できるようにするため。
            businessMetricsService.recordFailure(operation, elapsedMillis(startedAt));
            throw throwable;
        }
    }

    private String resolveOperation(ProceedingJoinPoint joinPoint) {
        // なぜ必要か: operation名をTodo業務操作の固定リストへ制限し、Datadogの系列爆発を防ぐため。
        final MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        final String methodName = signature.getMethod().getName();
        return switch (methodName) {
            case "listTodos" -> "todo.list";
            case "getTodo" -> "todo.get";
            case "createTodo" -> "todo.create";
            case "updateTodo" -> "todo.update";
            case "deleteTodo" -> "todo.delete";
            default -> "todo.unknown";
        };
    }

    private double elapsedMillis(long startedAt) {
        // なぜ必要か: 業務処理時間をms単位に正規化し、既存のtodo.operation.duration契約を維持するため。
        return (System.nanoTime() - startedAt) / 1_000_000d;
    }
}
