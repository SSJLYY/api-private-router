package org.apiprivaterouter.javabackend.admin.account.model;

import org.apiprivaterouter.javabackend.admin.dashboard.model.ModelStatResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.AdminUsageStatsResponse;

import java.util.List;

public record AccountUsageStatsResponse(
        List<AccountUsageHistoryResponse> history,
        AccountUsageSummaryResponse summary,
        List<ModelStatResponse> models,
        List<AdminUsageStatsResponse.EndpointStat> endpoints,
        List<AdminUsageStatsResponse.EndpointStat> upstream_endpoints
) {
}
