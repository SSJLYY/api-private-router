package org.apiprivaterouter.javabackend.admin.payment.model;

import java.util.List;

public record AdminPaymentConfigResponse(
        boolean enabled,
        double min_amount,
        double max_amount,
        double daily_limit,
        int order_timeout_minutes,
        int max_pending_orders,
        List<String> enabled_payment_types,
        boolean balance_disabled,
        double balance_recharge_multiplier,
        double recharge_fee_rate,
        String load_balance_strategy,
        String product_name_prefix,
        String product_name_suffix,
        String help_image_url,
        String help_text,
        String stripe_publishable_key
) {
}
