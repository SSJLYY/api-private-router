package org.apiprivaterouter.javabackend.admin.group.model;

public record GroupUsageSummaryResponse(
        long group_id,
        double today_cost,
        double total_cost
) {
}
