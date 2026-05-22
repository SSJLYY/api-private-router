package org.apiprivaterouter.javabackend.admin.dashboard.model;

import java.util.List;

public record DashboardSnapshotV2Response(
        String generated_at,
        String start_date,
        String end_date,
        String granularity,
        AdminDashboardStatsResponse stats,
        List<TrendDataPointResponse> trend,
        List<ModelStatResponse> models,
        List<GroupStatResponse> groups,
        List<UserUsageTrendPointResponse> users_trend
) {
}
