package org.apiprivaterouter.javabackend.admin.proxy.model;

public record ProxyStatsResponse(
        long total_accounts,
        long active_accounts,
        long total_requests,
        double success_rate,
        double average_latency
) {
}
