package org.apiprivaterouter.javabackend.admin.account.model;

public record ClaudeOAuthTokenResponse(
        String access_token,
        String token_type,
        long expires_in,
        long expires_at,
        String refresh_token,
        String scope,
        String org_uuid,
        String account_uuid,
        String email_address
) {
}
