package org.apiprivaterouter.javabackend.admin.gemini.model;

import java.util.Map;

public record GeminiOAuthTokenResponse(
        String access_token,
        String refresh_token,
        long expires_in,
        long expires_at,
        String token_type,
        String scope,
        String project_id,
        String oauth_type,
        String tier_id,
        Map<String, Object> extra
) {
}
