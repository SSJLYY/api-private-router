package org.apiprivaterouter.javabackend.gateway.runtime.model;

public record GatewayRuntimeContext(
        GatewayApiKeyRuntimeView apiKey,
        GatewayUserSummary user,
        GatewaySubscriptionSummary subscription,
        GatewayAccountSummary account
) {
}
