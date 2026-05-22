package org.apiprivaterouter.javabackend.auth.model;

public record PendingOAuthTokenPair(
        String accessToken,
        String refreshToken,
        int expiresIn,
        String tokenType,
        CurrentUserResponse user
) {
}
