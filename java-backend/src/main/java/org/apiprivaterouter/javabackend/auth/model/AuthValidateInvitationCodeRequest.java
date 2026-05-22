package org.apiprivaterouter.javabackend.auth.model;

import jakarta.validation.constraints.NotBlank;

public record AuthValidateInvitationCodeRequest(
        @NotBlank String code
) {
}
