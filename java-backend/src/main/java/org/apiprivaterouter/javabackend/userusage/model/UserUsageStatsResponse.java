package org.apiprivaterouter.javabackend.userusage.model;

public record UserUsageStatsResponse(
        long total_requests,
        long total_input_tokens,
        long total_output_tokens,
        long total_cache_tokens,
        long total_tokens,
        double total_cost,
        double total_actual_cost,
        double average_duration_ms
) {
}
