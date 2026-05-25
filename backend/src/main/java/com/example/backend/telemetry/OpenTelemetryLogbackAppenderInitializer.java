package com.example.backend.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

@Component
public class OpenTelemetryLogbackAppenderInitializer {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryLogbackAppenderInitializer.class);

    private final OpenTelemetry openTelemetry;
    private final Consumer<OpenTelemetry> appenderInstaller;

    @Autowired
    public OpenTelemetryLogbackAppenderInitializer(OpenTelemetry openTelemetry) {
        // なぜ必要か: 実行時に OpenTelemetryAppender.install を呼び、trace_id/span_id 相関の導線を標準化するため。
        this(openTelemetry, OpenTelemetryLogbackAppenderInitializer::installWithReflection);
    }

    OpenTelemetryLogbackAppenderInitializer(OpenTelemetry openTelemetry, Consumer<OpenTelemetry> appenderInstaller) {
        // なぜ必要か: 初期化失敗時のテスト容易性を確保し、劣化運転ポリシーを安全に検証できるようにするため。
        this.openTelemetry = openTelemetry;
        this.appenderInstaller = appenderInstaller;
    }

    @PostConstruct
    void install() {
        try {
            // なぜ必要か: Spring起動時に Logback Appender を有効化し、trace_id/span_id の相関キーを安定出力するため。
            appenderInstaller.accept(openTelemetry);
            log.info("OpenTelemetry Logback appender installed");
        } catch (RuntimeException runtimeException) {
            // なぜ必要か: 初期化失敗時でもアプリを停止せず原因をログで追跡可能にし、運用復旧を容易にするため。
            log.warn("OpenTelemetry Logback appender installation failed; continuing without appender", runtimeException);
        }
    }

    private static void installWithReflection(OpenTelemetry openTelemetry) {
        try {
            final Class<?> appenderClass = Class.forName(
                    "io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender");
            final Method installMethod = appenderClass.getMethod("install", OpenTelemetry.class);
            installMethod.invoke(null, openTelemetry);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("OpenTelemetryAppender install method is not available", exception);
        } catch (InvocationTargetException invocationTargetException) {
            final Throwable cause = invocationTargetException.getTargetException();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("OpenTelemetryAppender installation failed", cause);
        }
    }
}
