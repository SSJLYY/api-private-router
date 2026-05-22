package org.apiprivaterouter.javabackend.admin.group.model;

public record GroupCapacitySummaryResponse(
        long group_id,
        int concurrency_used,
        int concurrency_max,
        int sessions_used,
        int sessions_max,
        int rpm_used,
        int rpm_max
) {
}
