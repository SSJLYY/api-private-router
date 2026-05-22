package org.apiprivaterouter.javabackend.admin.promo.model;

public record AdminPromoCodeResponse(
        long id,
        String code,
        double bonus_amount,
        int max_uses,
        int used_count,
        String status,
        String expires_at,
        String notes,
        String created_at,
        String updated_at
) {
}
