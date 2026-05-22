package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

public record ContentModerationApiKeyStatus(
        int index,
        String key_hash,
        String masked,
        String status,
        int failure_count,
        long success_count,
        String last_error,
        String last_checked_at,
        String frozen_until,
        int last_latency_ms,
        int last_http_status,
        boolean last_tested,
        boolean configured
) {
}
