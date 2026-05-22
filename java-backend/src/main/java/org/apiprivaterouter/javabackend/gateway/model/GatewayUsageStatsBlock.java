package org.apiprivaterouter.javabackend.gateway.model;

public record GatewayUsageStatsBlock(
        GatewayUsageBucket today,
        GatewayUsageBucket total,
        double average_duration_ms,
        long rpm,
        long tpm
) {
}
