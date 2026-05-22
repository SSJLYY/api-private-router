package org.apiprivaterouter.javabackend.payment.model;

import java.util.Map;

public record PaymentWebhookNotification(
        String providerKey,
        String tradeNo,
        String orderId,
        double amount,
        String status,
        String rawData,
        Map<String, String> metadata
) {
    public boolean success() {
        return "success".equalsIgnoreCase(status);
    }
}
