package org.apiprivaterouter.javabackend.admin.openai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiExchangeCodeRequest(
        @NotBlank String session_id,
        @NotBlank String code,
        @NotBlank String state,
        String redirect_uri,
        Long proxy_id
) {
}
