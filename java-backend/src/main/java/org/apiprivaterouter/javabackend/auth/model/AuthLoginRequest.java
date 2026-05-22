package org.apiprivaterouter.javabackend.auth.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AuthLoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        String turnstile_token
) {
}
