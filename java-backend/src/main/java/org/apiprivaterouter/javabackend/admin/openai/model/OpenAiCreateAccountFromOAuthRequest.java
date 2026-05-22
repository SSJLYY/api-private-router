package org.apiprivaterouter.javabackend.admin.openai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiCreateAccountFromOAuthRequest(
        @NotBlank String session_id,
        @NotBlank String code,
        @NotBlank String state,
        String redirect_uri,
        Long proxy_id,
        String name,
        Integer concurrency,
        Integer priority,
        List<Long> group_ids
) {
}
