package org.apiprivaterouter.javabackend.payment.model;

public record PaymentConfigResponse(
        boolean payment_enabled,
        double min_amount,
        double max_amount,
        double daily_limit,
        int max_pending_orders,
        int order_timeout_minutes,
        boolean balance_disabled,
        double balance_recharge_multiplier,
        java.util.List<String> enabled_payment_types,
        double recharge_fee_rate,
        String help_text,
        String help_image_url,
        String stripe_publishable_key
) {
}
