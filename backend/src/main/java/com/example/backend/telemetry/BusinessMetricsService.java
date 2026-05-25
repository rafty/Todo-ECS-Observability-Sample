package com.example.backend.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BusinessMetricsService {

    private final LongCounter successCounter;
    private final LongCounter failureCounter;
    private final DoubleHistogram durationHistogram;
    private final String serviceName;
    private final String environment;

    public BusinessMetricsService(
            OpenTelemetry openTelemetry,
            @Value("${DD_SERVICE:todo-backend}") String serviceName,
            @Value("${DD_ENV:dev}") String environment
    ) {
        // なぜ必要か: business系メトリクスの命名空間を固定し、Datadog側での横断検索を安定させるため。
        final Meter meter = openTelemetry.getMeter("com.example.backend.business");
        this.serviceName = serviceName;
        this.environment = environment;

        // なぜ必要か: 業務処理成功件数をoperation単位で集計し、障害時の成功率低下を検知可能にするため。
        this.successCounter = meter.counterBuilder("business.operation.success.count")
                .setDescription("Number of successful business operations")
                .setUnit("{count}")
                .build();

        // なぜ必要か: 業務処理失敗件数をoperation単位で集計し、エラー増加を早期検知できるようにするため。
        this.failureCounter = meter.counterBuilder("business.operation.failure.count")
                .setDescription("Number of failed business operations")
                .setUnit("{count}")
                .build();

        // なぜ必要か: 業務処理時間を分布で把握し、p95/p99遅延監視に利用できるようにするため。
        this.durationHistogram = meter.histogramBuilder("business.operation.duration")
                .setDescription("Business operation duration in milliseconds")
                .setUnit("ms")
                .build();
    }

    public void recordSuccess(String operation, double durationMillis) {
        // なぜ必要か: 成功件数と処理時間を同一attributesで記録し、Datadog側で同軸分析できるようにするため。
        final Attributes attributes = attributes(operation, "success");
        successCounter.add(1, attributes);
        durationHistogram.record(durationMillis, attributes);
    }

    public void recordFailure(String operation, double durationMillis) {
        // なぜ必要か: 失敗件数と失敗時の処理時間を記録し、失敗傾向と遅延の相関を確認できるようにするため。
        final Attributes attributes = attributes(operation, "failure");
        failureCounter.add(1, attributes);
        durationHistogram.record(durationMillis, attributes);
    }

    private Attributes attributes(String operation, String result) {
        // なぜ必要か: 低カーディナリティ属性へ限定し、メトリクス課金と系列爆発を抑止するため。
        return Attributes.builder()
                .put("service", serviceName)
                .put("env", environment)
                .put("operation", operation)
                .put("result", result)
                .build();
    }
}
