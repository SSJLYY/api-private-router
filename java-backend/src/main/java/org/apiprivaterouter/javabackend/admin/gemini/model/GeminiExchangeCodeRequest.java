package org.apiprivaterouter.javabackend.admin.gemini.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiExchangeCodeRequest(
        @NotBlank String session_id,
        @NotBlank String state,
        @NotBlank String code,
        Long proxy_id,
        String oauth_type,
        String tier_id
) {
}
