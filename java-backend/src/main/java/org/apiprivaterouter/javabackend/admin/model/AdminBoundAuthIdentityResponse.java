package org.apiprivaterouter.javabackend.admin.model;

import java.util.Map;

public record AdminBoundAuthIdentityResponse(
        long user_id,
        String provider_type,
        String provider_key,
        String provider_subject,
        String verified_at,
        String issuer,
        Map<String, Object> metadata,
        String created_at,
        String updated_at,
        AdminBoundAuthIdentityChannelResponse channel
) {
    public record AdminBoundAuthIdentityChannelResponse(
            String channel,
            String channel_app_id,
            String channel_subject,
            Map<String, Object> metadata,
            String created_at,
            String updated_at
    ) {
    }
}
