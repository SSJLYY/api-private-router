package org.apiprivaterouter.javabackend.auth.model;

public record AuthLogoutRequest(
        String refresh_token
) {
}
