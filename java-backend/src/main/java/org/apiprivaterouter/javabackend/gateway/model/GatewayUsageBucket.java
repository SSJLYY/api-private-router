package org.apiprivaterouter.javabackend.gateway.model;

public record GatewayUsageBucket(
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
