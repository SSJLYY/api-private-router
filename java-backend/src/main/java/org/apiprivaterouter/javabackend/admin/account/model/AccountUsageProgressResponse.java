package org.apiprivaterouter.javabackend.admin.account.model;

public record AccountUsageProgressResponse(
        double utilization,
        String resets_at,
        int remaining_seconds,
        WindowStatsResponse window_stats,
        Long used_requests,
        Long limit_requests
) {
}
