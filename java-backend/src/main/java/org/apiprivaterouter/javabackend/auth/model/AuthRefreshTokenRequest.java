package org.apiprivaterouter.javabackend.auth.model;

import jakarta.validation.constraints.NotBlank;

public record AuthRefreshTokenRequest(
        @NotBlank String refresh_token
) {
}
