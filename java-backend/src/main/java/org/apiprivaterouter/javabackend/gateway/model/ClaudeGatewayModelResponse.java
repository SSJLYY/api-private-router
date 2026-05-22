package org.apiprivaterouter.javabackend.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClaudeGatewayModelResponse(
        String id,
        String type,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("created_at") String createdAt
) {
}
