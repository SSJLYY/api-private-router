package org.apiprivaterouter.javabackend.userusage.model;

public record UserDashboardStatsResponse(
        long total_api_keys,
        long active_api_keys,
        long total_requests,
        long total_input_tokens,
        long total_output_tokens,
        long total_cache_creation_tokens,
        long total_cache_read_tokens,
        long total_tokens,
        double total_cost,
        double total_actual_cost,
        long today_requests,
        long today_input_tokens,
        long today_output_tokens,
        long today_cache_creation_tokens,
        long today_cache_read_tokens,
        long today_tokens,
        double today_cost,
        double today_actual_cost,
        double average_duration_ms,
        long rpm,
        long tpm
) {
}
