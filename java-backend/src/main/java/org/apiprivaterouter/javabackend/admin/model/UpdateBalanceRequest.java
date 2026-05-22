package org.apiprivaterouter.javabackend.admin.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record UpdateBalanceRequest(
        @Positive double balance,
        @NotBlank String operation,
        String notes
) {
}
