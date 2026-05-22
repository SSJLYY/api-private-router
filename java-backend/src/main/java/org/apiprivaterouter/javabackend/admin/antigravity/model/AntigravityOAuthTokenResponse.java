package org.apiprivaterouter.javabackend.admin.antigravity.model;

public record AntigravityOAuthTokenResponse(
        String access_token,
        String refresh_token,
        long expires_in,
        long expires_at,
        String token_type,
        String email,
        String project_id,
        String plan_type,
        String privacy_mode
) {
}
