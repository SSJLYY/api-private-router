package org.apiprivaterouter.javabackend.userbindings.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SendEmailBindingCodeRequest(
        @NotBlank @Email String email
) {
}
