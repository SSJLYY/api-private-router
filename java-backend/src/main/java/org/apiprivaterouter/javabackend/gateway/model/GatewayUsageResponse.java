package org.apiprivaterouter.javabackend.gateway.model;

public record GatewayUsageResponse(
        String mode,
        boolean isValid,
        String status,
        String planName,
        Double remaining,
        String unit,
        Double balance,
        GatewayQuotaResponse quota,
        java.util.List<GatewayRateLimitResponse> rate_limits,
        GatewayUsageStatsBlock usage,
        Object model_stats,
        GatewaySubscriptionUsageResponse subscription,
        String expires_at,
        Integer days_until_expiry
) {
}
