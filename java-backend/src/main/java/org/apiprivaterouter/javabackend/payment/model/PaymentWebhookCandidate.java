package org.apiprivaterouter.javabackend.payment.model;

import java.util.Map;

public record PaymentWebhookCandidate(
        String instanceId,
        String providerKey,
        String paymentMode,
        boolean enabled,
        Map<String, String> config
) {
}
