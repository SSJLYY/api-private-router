package org.apiprivaterouter.javabackend.admin.subscription.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AssignSubscriptionRequest(
        @NotNull @Positive Long user_id,
        @NotNull @Positive Long group_id,
        @Positive Integer validity_days,
        String notes
) {
}
