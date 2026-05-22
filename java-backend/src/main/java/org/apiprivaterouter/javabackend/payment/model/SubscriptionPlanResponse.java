package org.apiprivaterouter.javabackend.payment.model;

import java.util.List;

public record SubscriptionPlanResponse(
        long id,
        long group_id,
        String group_platform,
        String group_name,
        Double rate_multiplier,
        Double daily_limit_usd,
        Double weekly_limit_usd,
        Double monthly_limit_usd,
        List<String> supported_model_scopes,
        String name,
        String description,
        double price,
        Double original_price,
        int validity_days,
        String validity_unit,
        List<String> features,
        String product_name,
        boolean for_sale,
        int sort_order
) {
}
