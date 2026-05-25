package com.example.backend.telemetry;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class TelemetryEnvironmentValidator {

    private static final Set<String> ALLOWED_ENVIRONMENTS = Set.of("dev", "stg", "prod");

    private final String ddEnvironment;

    public TelemetryEnvironmentValidator(@Value("${DD_ENV:dev}") String ddEnvironment) {
        this.ddEnvironment = ddEnvironment;
    }

    @PostConstruct
    void validate() {
        // なぜ必要か: DD_ENV の許容値を起動時に固定し、タグ不整合によるDatadog相関崩れを早期検知するため。
        if (!ALLOWED_ENVIRONMENTS.contains(ddEnvironment)) {
            throw new IllegalStateException("DD_ENV must be one of dev|stg|prod but was: " + ddEnvironment);
        }
    }
}
