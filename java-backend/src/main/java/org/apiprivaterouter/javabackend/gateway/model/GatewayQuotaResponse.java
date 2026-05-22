package org.apiprivaterouter.javabackend.gateway.model;

public record GatewayQuotaResponse(
        double limit,
        double used,
        double remaining,
        String unit
) {
}
