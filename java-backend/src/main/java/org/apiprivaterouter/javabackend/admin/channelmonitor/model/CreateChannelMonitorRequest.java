package org.apiprivaterouter.javabackend.admin.channelmonitor.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record CreateChannelMonitorRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank String provider,
        @NotBlank @Size(max = 500) String endpoint,
        @NotBlank @Size(max = 2000) String api_key,
        @NotBlank @Size(max = 200) String primary_model,
        List<String> extra_models,
        @Size(max = 100) String group_name,
        Boolean enabled,
        @NotNull @Min(15) @Max(3600) Integer interval_seconds,
        Long template_id,
        Map<String, String> extra_headers,
        String body_override_mode,
        Map<String, Object> body_override
) {
}
