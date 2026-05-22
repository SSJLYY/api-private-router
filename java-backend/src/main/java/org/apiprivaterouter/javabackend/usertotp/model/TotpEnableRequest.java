package org.apiprivaterouter.javabackend.usertotp.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TotpEnableRequest(
        @NotBlank String setup_token,
        @NotBlank @Pattern(regexp = "\\d{6}") String totp_code
) {
}
