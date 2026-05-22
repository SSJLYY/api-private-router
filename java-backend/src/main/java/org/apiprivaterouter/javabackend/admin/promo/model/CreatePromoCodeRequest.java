package org.apiprivaterouter.javabackend.admin.promo.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreatePromoCodeRequest(
        String code,
        @NotNull @DecimalMin("0.0") Double bonus_amount,
        @Min(0) Integer max_uses,
        Long expires_at,
        String notes
) {
}
