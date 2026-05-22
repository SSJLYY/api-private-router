package org.apiprivaterouter.javabackend.admin.usage.model;

import java.util.List;

public record AdminUsageStatsResponse(
        long total_requests,
        long total_input_tokens,
        long total_output_tokens,
        long total_cache_tokens,
        long total_tokens,
        double total_cost,
        double total_actual_cost,
        double total_account_cost,
        double average_duration_ms,
        List<EndpointStat> endpoints,
        List<EndpointStat> upstream_endpoints,
        List<EndpointStat> endpoint_paths
) {
    public record EndpointStat(
            String endpoint,
            long requests,
            long total_tokens,
            double cost,
            double actual_cost
    ) {
    }
}
