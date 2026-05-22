package org.apiprivaterouter.javabackend.admin.antigravity.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AntigravityExchangeCodeRequest(
        @NotBlank String session_id,
        @NotBlank String state,
        @NotBlank String code,
        Long proxy_id
) {
}
