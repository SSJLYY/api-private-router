package org.apiprivaterouter.javabackend.admin.gemini.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiAuthUrlRequest(
        Long proxy_id,
        String project_id,
        String oauth_type,
        String tier_id
) {
}
