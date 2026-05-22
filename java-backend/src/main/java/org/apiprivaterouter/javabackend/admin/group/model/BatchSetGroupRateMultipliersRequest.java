package org.apiprivaterouter.javabackend.admin.group.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record BatchSetGroupRateMultipliersRequest(
        @NotEmpty List<@Valid Entry> entries
) {
    public record Entry(
            @Positive long user_id,
            @DecimalMin(value = "0.0001", inclusive = true) double rate_multiplier
    ) {
    }
}
