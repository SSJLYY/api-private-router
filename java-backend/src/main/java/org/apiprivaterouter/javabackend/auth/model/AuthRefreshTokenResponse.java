package org.apiprivaterouter.javabackend.auth.model;

public record AuthRefreshTokenResponse(
        String access_token,
        String refresh_token,
        int expires_in,
        String token_type
) {
}
