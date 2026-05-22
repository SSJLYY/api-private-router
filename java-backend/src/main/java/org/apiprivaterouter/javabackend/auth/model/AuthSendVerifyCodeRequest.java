package org.apiprivaterouter.javabackend.auth.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AuthSendVerifyCodeRequest(
        @NotBlank @Email String email,
        String turnstile_token
) {
}
