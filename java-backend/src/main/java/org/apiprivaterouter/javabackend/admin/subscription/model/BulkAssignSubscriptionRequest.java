package org.apiprivaterouter.javabackend.admin.subscription.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record BulkAssignSubscriptionRequest(
        @NotEmpty List<@Positive Long> user_ids,
        @NotNull @Positive Long group_id,
        @Positive Integer validity_days,
        String notes
) {
}
