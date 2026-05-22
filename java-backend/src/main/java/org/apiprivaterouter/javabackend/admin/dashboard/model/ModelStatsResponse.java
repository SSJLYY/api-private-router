package org.apiprivaterouter.javabackend.admin.dashboard.model;

import java.util.List;

public record ModelStatsResponse(
        List<ModelStatResponse> models,
        String start_date,
        String end_date
) {
}
