package org.apiprivaterouter.javabackend.admin.channelmonitor.model;

import java.util.List;
import java.util.Map;

public record ChannelMonitorResponse(
        long id,
        String name,
        String provider,
        String endpoint,
        String api_key_masked,
        boolean api_key_decrypt_failed,
        String primary_model,
        List<String> extra_models,
        String group_name,
        boolean enabled,
        int interval_seconds,
        String last_checked_at,
        long created_by,
        String created_at,
        String updated_at,
        String primary_status,
        Integer primary_latency_ms,
        double availability_7d,
        List<ExtraModelStatusResponse> extra_models_status,
        Long template_id,
        Map<String, String> extra_headers,
        String body_override_mode,
        Map<String, Object> body_override
) {
}
