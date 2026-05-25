package com.example.backend.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class BusinessMetricsServiceTest {

    @Test
    void shouldRecordSuccessAndFailureWithoutThrowing() {
        final BusinessMetricsService businessMetricsService = new BusinessMetricsService(
                OpenTelemetry.noop(),
                "todo-backend",
                "dev"
        );

        // なぜ必要か: metrics実装が最小構成でも例外なく動作し、業務処理を巻き込んで失敗しないことを担保するため。
        assertThatCode(() -> {
            businessMetricsService.recordSuccess("todo.create", 12.5d);
            businessMetricsService.recordFailure("todo.create", 8.0d);
        }).doesNotThrowAnyException();
    }
}
