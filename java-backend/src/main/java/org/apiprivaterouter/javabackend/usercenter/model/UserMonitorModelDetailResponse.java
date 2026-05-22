package org.apiprivaterouter.javabackend.usercenter.model;

public record UserMonitorModelDetailResponse(
        String model,
        String latest_status,
        Integer latest_latency_ms,
        double availability_7d,
        double availability_15d,
        double availability_30d,
        Integer avg_latency_7d_ms
) {
}
