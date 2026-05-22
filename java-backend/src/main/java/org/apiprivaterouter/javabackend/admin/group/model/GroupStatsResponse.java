package org.apiprivaterouter.javabackend.admin.group.model;

public record GroupStatsResponse(
        long total_api_keys,
        long active_api_keys,
        long total_requests,
        double total_cost
) {
}
