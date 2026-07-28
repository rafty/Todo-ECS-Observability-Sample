package com.example.backend.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenTelemetryApiConfig {

    @Bean
    OpenTelemetry openTelemetry() {
        // なぜ必要か: Java Agentが設定するGlobalOpenTelemetryを参照し、アプリ内に別SDK/exporterを起動しないため。
        return GlobalOpenTelemetry.get();
    }
}
