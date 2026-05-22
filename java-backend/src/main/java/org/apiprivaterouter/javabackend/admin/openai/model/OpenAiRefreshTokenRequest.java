package org.apiprivaterouter.javabackend.admin.openai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiRefreshTokenRequest(
        String refresh_token,
        String rt,
        String client_id,
        Long proxy_id
) {
}
