package org.apiprivaterouter.javabackend.userbindings.model;

import jakarta.validation.constraints.NotBlank;

public record StartIdentityBindingRequest(
        @NotBlank String provider,
        String redirect_to
) {
}
