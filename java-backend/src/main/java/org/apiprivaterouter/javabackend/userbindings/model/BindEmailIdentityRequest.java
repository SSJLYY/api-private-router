package org.apiprivaterouter.javabackend.userbindings.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record BindEmailIdentityRequest(
        @NotBlank @Email String email,
        @NotBlank String verify_code,
        @NotBlank String password
) {
}
