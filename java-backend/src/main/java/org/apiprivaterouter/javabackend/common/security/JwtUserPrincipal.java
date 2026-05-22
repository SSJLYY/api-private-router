package org.apiprivaterouter.javabackend.common.security;

public record JwtUserPrincipal(
        long userId,
        String email,
        String role,
        long tokenVersion
) {
}
