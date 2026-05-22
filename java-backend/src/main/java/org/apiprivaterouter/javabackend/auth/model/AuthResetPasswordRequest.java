package org.apiprivaterouter.javabackend.auth.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthResetPasswordRequest(
        @NotBlank @Email String email,
        @NotBlank String token,
        @NotBlank @Size(min = 6) String new_password
) {
}
