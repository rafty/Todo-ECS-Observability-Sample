package com.example.backend.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class OpenTelemetryLogbackAppenderInitializerTest {

    @Test
    void shouldInstallAppenderOnStartup(CapturedOutput output) {
        final OpenTelemetry openTelemetry = mock(OpenTelemetry.class);
        final AtomicBoolean installed = new AtomicBoolean(false);

        // なぜ必要か: 起動時に install が呼ばれることを保証し、相関キー欠落の回帰を防ぐため。
        final OpenTelemetryLogbackAppenderInitializer initializer =
                new OpenTelemetryLogbackAppenderInitializer(openTelemetry, ignored -> installed.set(true));

        initializer.install();

        assertThat(installed).isTrue();
        assertThat(output.getOut()).contains("OpenTelemetry Logback appender installed");
    }

    @Test
    void shouldContinueWhenAppenderInstallFails(CapturedOutput output) {
        final OpenTelemetry openTelemetry = mock(OpenTelemetry.class);

        // なぜ必要か: 初期化失敗時にアプリ停止せず WARN ログで追跡できる劣化運転を担保するため。
        final OpenTelemetryLogbackAppenderInitializer initializer =
                new OpenTelemetryLogbackAppenderInitializer(openTelemetry, ignored -> {
                    throw new IllegalStateException("install-failed");
                });

        initializer.install();

        assertThat(output.getOut())
                .contains("OpenTelemetry Logback appender installation failed; continuing without appender")
                .contains("install-failed");
    }
}
