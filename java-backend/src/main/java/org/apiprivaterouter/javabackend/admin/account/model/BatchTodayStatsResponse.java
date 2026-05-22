package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.Map;

public record BatchTodayStatsResponse(
        Map<String, WindowStatsResponse> stats
) {
}
