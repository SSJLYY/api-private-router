package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TestContentModerationApiKeysRequest(
        List<String> api_keys,
        String base_url,
        String model,
        Integer timeout_ms,
        String prompt,
        List<String> images
) {
}
