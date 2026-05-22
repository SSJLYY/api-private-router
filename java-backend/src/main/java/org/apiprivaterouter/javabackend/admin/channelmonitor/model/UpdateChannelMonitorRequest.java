package org.apiprivaterouter.javabackend.admin.channelmonitor.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record UpdateChannelMonitorRequest(
        @Size(max = 100) String name,
        String provider,
        @Size(max = 500) String endpoint,
        @Size(max = 2000) String api_key,
        @Size(max = 200) String primary_model,
        List<String> extra_models,
        @Size(max = 100) String group_name,
        Boolean enabled,
        @Min(15) @Max(3600) Integer interval_seconds,
        Long template_id,
        Boolean clear_template,
        Map<String, String> extra_headers,
        String body_override_mode,
        Map<String, Object> body_override
) {
}
