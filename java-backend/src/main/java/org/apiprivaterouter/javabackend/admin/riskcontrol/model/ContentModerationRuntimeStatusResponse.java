package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

import java.util.List;

public record ContentModerationRuntimeStatusResponse(
        boolean enabled,
        boolean risk_control_enabled,
        String mode,
        int worker_count,
        int max_workers,
        int active_workers,
        int idle_workers,
        int queue_size,
        int queue_length,
        double queue_usage_percent,
        long enqueued,
        long dropped,
        long processed,
        long errors,
        List<ContentModerationApiKeyStatus> api_key_statuses,
        long flagged_hash_count,
        String last_cleanup_at,
        long last_cleanup_deleted_hit,
        long last_cleanup_deleted_non_hit
) {
}
