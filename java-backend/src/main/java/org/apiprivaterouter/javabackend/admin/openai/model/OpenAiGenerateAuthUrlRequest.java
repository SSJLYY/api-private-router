package org.apiprivaterouter.javabackend.admin.openai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiGenerateAuthUrlRequest(
        Long proxy_id,
        String redirect_uri
) {
}
