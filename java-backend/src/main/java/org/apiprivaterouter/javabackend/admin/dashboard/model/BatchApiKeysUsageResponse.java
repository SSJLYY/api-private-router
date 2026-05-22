package org.apiprivaterouter.javabackend.admin.dashboard.model;

import java.util.Map;

public record BatchApiKeysUsageResponse(
        Map<String, BatchApiKeyUsageStats> stats
) {
    public record BatchApiKeyUsageStats(
            long api_key_id,
            double today_actual_cost,
            double total_actual_cost
    ) {
    }
}
