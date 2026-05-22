package org.apiprivaterouter.javabackend.gateway.model;

public record GatewaySubscriptionUsageResponse(
        double daily_usage_usd,
        double weekly_usage_usd,
        double monthly_usage_usd,
        Double daily_limit_usd,
        Double weekly_limit_usd,
        Double monthly_limit_usd,
        String expires_at
) {
}
