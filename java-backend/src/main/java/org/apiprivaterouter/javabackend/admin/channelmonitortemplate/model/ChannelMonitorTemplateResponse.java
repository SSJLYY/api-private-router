package org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model;

import java.util.Map;

public record ChannelMonitorTemplateResponse(
        long id,
        String name,
        String provider,
        String description,
        Map<String, String> extra_headers,
        String body_override_mode,
        Map<String, Object> body_override,
        String created_at,
        String updated_at,
        long associated_monitors
) {
}
