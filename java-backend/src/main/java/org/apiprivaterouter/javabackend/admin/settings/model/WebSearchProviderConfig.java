package org.apiprivaterouter.javabackend.admin.settings.model;

public record WebSearchProviderConfig(
        String type,
        String api_key,
        boolean api_key_configured,
        Long quota_limit,
        Long subscribed_at,
        Long quota_used,
        Long proxy_id,
        Long expires_at
) {
}
