package org.apiprivaterouter.javabackend.admin.payment.model;

import java.util.List;

public record UpdateAdminPaymentConfigRequest(
        Boolean enabled,
        Double min_amount,
        Double max_amount,
        Double daily_limit,
        Integer order_timeout_minutes,
        Integer max_pending_orders,
        List<String> enabled_payment_types,
        Boolean balance_disabled,
        Double balance_recharge_multiplier,
        Double recharge_fee_rate,
        String load_balance_strategy,
        String product_name_prefix,
        String product_name_suffix,
        String help_image_url,
        String help_text
) {
}
