package org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateChannelMonitorTemplateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank String provider,
        @Size(max = 500) String description,
        Map<String, String> extra_headers,
        String body_override_mode,
        Map<String, Object> body_override
) {
}
