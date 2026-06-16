package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateContentModerationConfigRequest(
        Boolean enabled,
        String mode,
        String base_url,
        String model,
        String api_key,
        List<String> api_keys,
        Boolean clear_api_key,
        Integer timeout_ms,
        Integer sample_rate,
        Boolean all_groups,
        List<Long> group_ids,
        Boolean record_non_hits,
        Integer worker_count,
        Integer queue_size,
        Integer block_status,
        String block_message,
        Boolean email_on_hit,
        Boolean auto_ban_enabled,
        Integer ban_threshold,
        Integer violation_window_hours,
        Integer retry_count,
        Integer hit_retention_days,
        Integer non_hit_retention_days,
        Boolean pre_hash_check_enabled,
        List<String> blocked_keywords,
        String keyword_blocking_mode,
        ContentModerationModelFilterRequest model_filter
) {
}
