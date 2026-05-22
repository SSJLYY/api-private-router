package org.apiprivaterouter.javabackend.admin.promo.model;

import java.util.Map;

public record AdminPromoCodeUsageResponse(
        long id,
        long promo_code_id,
        long user_id,
        double bonus_amount,
        String used_at,
        Map<String, Object> user
) {
}
