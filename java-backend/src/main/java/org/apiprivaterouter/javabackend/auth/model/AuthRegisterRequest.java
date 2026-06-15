package org.apiprivaterouter.javabackend.auth.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 6, max = 128) String password,
        String verify_code,
        String turnstile_token,
        String promo_code,
        String invitation_code,
        String aff_code
) {
}
