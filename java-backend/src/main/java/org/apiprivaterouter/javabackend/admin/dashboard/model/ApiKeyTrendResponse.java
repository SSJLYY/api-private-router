package org.apiprivaterouter.javabackend.admin.dashboard.model;

import java.util.List;

public record ApiKeyTrendResponse(
        List<ApiKeyUsageTrendPointResponse> trend,
        String start_date,
        String end_date,
        String granularity
) {
}
