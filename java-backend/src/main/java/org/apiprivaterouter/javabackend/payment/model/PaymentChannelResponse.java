package org.apiprivaterouter.javabackend.payment.model;

public record PaymentChannelResponse(
        long id,
        Long group_id,
        String name,
        String platform,
        double rate_multiplier,
        String description,
        java.util.List<String> models,
        java.util.List<String> features,
        boolean enabled,
        String payment_type,
        String payment_mode
) {
}
