package org.apiprivaterouter.javabackend.gateway.runtime.model;

public record GatewayUserSummary(
        long userId,
        String email,
        String role,
        String status,
        double balance
) {
}
