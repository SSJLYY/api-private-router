package org.apiprivaterouter.javabackend.admin.dashboard.model;

public record TrendDataPointResponse(
        String date,
        long requests,
        long input_tokens,
        long output_tokens,
        long cache_creation_tokens,
        long cache_read_tokens,
        long total_tokens,
        double cost,
        double actual_cost
) {
}
