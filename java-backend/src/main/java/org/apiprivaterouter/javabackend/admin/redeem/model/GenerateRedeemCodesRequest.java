package org.apiprivaterouter.javabackend.admin.redeem.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record GenerateRedeemCodesRequest(
        @NotNull @Min(1) @Max(100) Integer count,
        @NotNull String type,
        @NotNull Double value,
        Long group_id,
        Integer validity_days
) {
}
