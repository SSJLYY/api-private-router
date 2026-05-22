package org.apiprivaterouter.javabackend.payment.model;

public record PaymentWebhookResult(
        String resolvedProviderKey,
        boolean verified,
        boolean acknowledged,
        boolean handled
) {
}
