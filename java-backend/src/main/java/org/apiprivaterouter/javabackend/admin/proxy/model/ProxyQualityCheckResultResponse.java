package org.apiprivaterouter.javabackend.admin.proxy.model;

import java.util.List;

public record ProxyQualityCheckResultResponse(
        long proxy_id,
        int score,
        String grade,
        String summary,
        String exit_ip,
        String country,
        String country_code,
        Long base_latency_ms,
        int passed_count,
        int warn_count,
        int failed_count,
        int challenge_count,
        long checked_at,
        List<ProxyQualityCheckItemResponse> items
) {
}
