package org.apiprivaterouter.javabackend.userusage.model;

public record UserErrorDetailResponse(
        long id,
        String requestId,
        String requestType,
        String model,
        String upstreamModel,
        int statusCode,
        String errorCode,
        String errorMessage,
        String createdAt,
        String inputPreview,
        String requestBody,
        String responseBody,
        String upstreamUrl
) {
}
