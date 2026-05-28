package com.example.backend.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BusinessMetricsServiceTest {

    @Test
    void shouldRecordSuccessAndFailureWithoutThrowing() {
        // なぜ必要か: Micrometer のメモリ実装を使い、副作用なく business metrics の記録結果を検証するため。
        final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        final BusinessMetricsService businessMetricsService = new BusinessMetricsService(
                meterRegistry,
                "todo-backend",
                "dev"
        );

        // なぜ必要か: metrics実装が最小構成でも例外なく動作し、業務処理を巻き込んで失敗しないことを担保するため。
        assertThatCode(() -> {
            businessMetricsService.recordSuccess("todo.create", 12.5d);
            businessMetricsService.recordFailure("todo.create", 8.0d);
        }).doesNotThrowAnyException();

        // なぜ必要か: 成功件数メトリクスが正しいタグで1件加算されることを担保するため。
        final Counter successCounter = meterRegistry.get("todo.operation.count")
                .tags("service", "todo-backend", "env", "dev", "operation", "todo.create", "result", "success")
                .counter();
        assertThat(successCounter.count()).isEqualTo(1d);

        // なぜ必要か: 失敗件数メトリクスが正しいタグで1件加算されることを担保するため。
        final Counter failureCounter = meterRegistry.get("todo.operation.count")
                .tags("service", "todo-backend", "env", "dev", "operation", "todo.create", "result", "failure")
                .counter();
        assertThat(failureCounter.count()).isEqualTo(1d);

        // なぜ必要か: 処理時間メトリクスが result ごとに独立集計され、値が毀損していないことを担保するため。
        final DistributionSummary successDuration = meterRegistry.get("todo.operation.duration")
                .tags("service", "todo-backend", "env", "dev", "operation", "todo.create", "result", "success")
                .summary();
        final DistributionSummary failureDuration = meterRegistry.get("todo.operation.duration")
                .tags("service", "todo-backend", "env", "dev", "operation", "todo.create", "result", "failure")
                .summary();
        assertThat(successDuration.count()).isEqualTo(1L);
        assertThat(successDuration.totalAmount()).isCloseTo(12.5d, within(0.0001d));
        assertThat(failureDuration.count()).isEqualTo(1L);
        assertThat(failureDuration.totalAmount()).isCloseTo(8.0d, within(0.0001d));
    }
}
