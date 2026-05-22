package org.apiprivaterouter.javabackend.auth.model;

import jakarta.validation.constraints.NotBlank;

public record AuthValidatePromoCodeRequest(
        @NotBlank String code
) {
}
