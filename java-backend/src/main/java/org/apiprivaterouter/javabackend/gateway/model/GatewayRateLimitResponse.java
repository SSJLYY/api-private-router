package org.apiprivaterouter.javabackend.gateway.model;

public record GatewayRateLimitResponse(
        String window,
        double limit,
        double used,
        double remaining,
        String window_start,
        String reset_at
) {
}
