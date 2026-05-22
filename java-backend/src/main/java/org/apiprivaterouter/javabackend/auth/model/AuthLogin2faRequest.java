package org.apiprivaterouter.javabackend.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AuthLogin2faRequest(
        @NotBlank String temp_token,
        @NotBlank @Pattern(regexp = "\\d{6}") String totp_code
) {
}
