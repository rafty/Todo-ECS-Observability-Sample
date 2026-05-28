package com.example.backend.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BusinessMetricsService {

    private static final String OPERATION_COUNT_METRIC_NAME = "todo.operation.count";
    private static final String DURATION_METRIC_NAME = "todo.operation.duration";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILURE = "failure";

    private final MeterRegistry meterRegistry;
    private final String serviceTag;
    private final String environmentTag;

    public BusinessMetricsService(
            MeterRegistry meterRegistry,
            @Value("${DD_SERVICE:todo-backend}") String serviceName,
            @Value("${DD_ENV:dev}") String environment
    ) {
        // なぜ必要か: Spring Boot 標準の Micrometer 基盤へ集約し、OTel Metrics API と二重運用しないため。
        this.meterRegistry = meterRegistry;
        // なぜ必要か: Unified Service Tagging 軸をアプリ内メトリクスへ固定付与するため。
        this.serviceTag = serviceName;
        this.environmentTag = environment;
    }

    public void recordSuccess(String operation, double durationMillis) {
        // なぜ必要か: 成功件数と処理時間を同一タグで記録し、Datadog 上で成功率と遅延を同軸分析できるようにするため。
        final Tags tags = metricTags(operation, RESULT_SUCCESS);
        operationCounter(tags).increment();
        durationSummary(tags).record(durationMillis);
    }

    public void recordFailure(String operation, double durationMillis) {
        // なぜ必要か: 失敗件数と失敗時の処理時間を同一タグで記録し、障害時の傾向分析を容易にするため。
        final Tags tags = metricTags(operation, RESULT_FAILURE);
        operationCounter(tags).increment();
        durationSummary(tags).record(durationMillis);
    }

    private Counter operationCounter(Tags tags) {
        // なぜ必要か: Todo操作件数を result タグで統一集計し、メトリクス名だけで意味が伝わる形にするため。
        return Counter.builder(OPERATION_COUNT_METRIC_NAME)
                .description("Number of todo operations")
                .baseUnit("{count}")
                .tags(tags)
                .register(meterRegistry);
    }

    private DistributionSummary durationSummary(Tags tags) {
        // なぜ必要か: 処理時間分布を ms 単位で保存し、p95/p99 や異常遅延の検知に利用するため。
        return DistributionSummary.builder(DURATION_METRIC_NAME)
                .description("Todo operation duration in milliseconds")
                .baseUnit("ms")
                .tags(tags)
                .register(meterRegistry);
    }

    private Tags metricTags(String operation, String result) {
        // なぜ必要か: タグを低カーディナリティに制限し、メトリクス系列爆発と不要課金を防ぐため。
        return Tags.of(
                "service", serviceTag,
                "env", environmentTag,
                "operation", operation,
                "result", result
        );
    }
}
