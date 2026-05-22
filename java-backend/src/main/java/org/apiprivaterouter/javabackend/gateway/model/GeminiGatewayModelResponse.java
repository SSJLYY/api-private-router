package org.apiprivaterouter.javabackend.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GeminiGatewayModelResponse(
        String name,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("supportedGenerationMethods") List<String> supportedGenerationMethods
) {
}
