package org.apiprivaterouter.javabackend.gateway.runtime.model;

public record GatewayApiKeyRuntimeView(
        long id,
        long userId,
        String key,
        String name,
        String status,
        Long groupId,
        double quota,
        double quotaUsed,
        String expiresAt,
        double rateLimit5h,
        double rateLimit1d,
        double rateLimit7d,
        double usage5h,
        double usage1d,
        double usage7d,
        String window5hStart,
        String window1dStart,
        String window7dStart,
        String reset5hAt,
        String reset1dAt,
        String reset7dAt,
        GatewayGroupSummary group
) {
}
