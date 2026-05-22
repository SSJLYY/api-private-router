package org.apiprivaterouter.javabackend.admin.dashboard.model;

import java.util.Map;

public record BatchUsersUsageResponse(
        Map<String, BatchUserUsageStats> stats
) {
    public record BatchUserUsageStats(
            long user_id,
            double today_actual_cost,
            double total_actual_cost
    ) {
    }
}
