package org.apiprivaterouter.javabackend.admin.dashboard.model;

import jakarta.validation.constraints.NotBlank;

public record DashboardAggregationBackfillRequest(
        @NotBlank String start,
        @NotBlank String end
) {
}
