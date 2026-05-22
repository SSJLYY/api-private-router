package org.apiprivaterouter.javabackend.gateway.model;

import java.util.List;

public record GeminiGatewayModelsResponse(
        List<GeminiGatewayModelResponse> models
) {
}
