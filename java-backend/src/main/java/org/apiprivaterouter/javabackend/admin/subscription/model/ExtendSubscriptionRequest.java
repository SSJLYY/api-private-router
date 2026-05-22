package org.apiprivaterouter.javabackend.admin.subscription.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ExtendSubscriptionRequest(
        @Min(-36500) @Max(36500) int days
) {
}
