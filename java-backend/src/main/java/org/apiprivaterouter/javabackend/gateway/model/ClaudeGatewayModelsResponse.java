package org.apiprivaterouter.javabackend.gateway.model;

import java.util.List;

public record ClaudeGatewayModelsResponse(
        String object,
        List<ClaudeGatewayModelResponse> data
) {
}
