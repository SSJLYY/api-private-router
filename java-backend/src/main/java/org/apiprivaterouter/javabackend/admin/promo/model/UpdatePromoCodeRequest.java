package org.apiprivaterouter.javabackend.admin.promo.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record UpdatePromoCodeRequest(
        String code,
        @DecimalMin("0.0") Double bonus_amount,
        @Min(0) Integer max_uses,
        @Pattern(regexp = "active|disabled") String status,
        Long expires_at,
        String notes
) {
}
