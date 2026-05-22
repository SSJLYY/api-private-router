package org.apiprivaterouter.javabackend.admin.model;

import java.util.Map;

public record BindUserAuthIdentityRequest(
        String provider_type,
        String provider_key,
        String provider_subject,
        String issuer,
        Map<String, Object> metadata,
        BindUserAuthIdentityChannelRequest channel
) {
    public record BindUserAuthIdentityChannelRequest(
            String channel,
            String channel_app_id,
            String channel_subject,
            Map<String, Object> metadata
    ) {
    }
}
