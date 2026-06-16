package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

import java.util.List;
import java.util.Map;

public record ContentModerationConfigResponse(
        boolean enabled,
        String mode,
        String base_url,
        String model,
        boolean api_key_configured,
        String api_key_masked,
        int api_key_count,
        List<String> api_key_masks,
        List<ContentModerationApiKeyStatus> api_key_statuses,
        int timeout_ms,
        int sample_rate,
        boolean all_groups,
        List<Long> group_ids,
        boolean record_non_hits,
        int worker_count,
        int queue_size,
        int block_status,
        String block_message,
        boolean email_on_hit,
        boolean auto_ban_enabled,
        int ban_threshold,
        int violation_window_hours,
        int retry_count,
        int hit_retention_days,
        int non_hit_retention_days,
        boolean pre_hash_check_enabled,
        List<String> blocked_keywords,
        String keyword_blocking_mode,
        Map<String, Double> thresholds,
        ContentModerationModelFilterResponse model_filter
) {
}
