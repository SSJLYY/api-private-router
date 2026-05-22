package org.apiprivaterouter.javabackend.admin.dashboard.model;

import java.util.List;

public record GroupStatsResponse(
        List<GroupStatResponse> groups,
        String start_date,
        String end_date
) {
}
