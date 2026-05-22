package org.apiprivaterouter.javabackend.riskcontrol.runtime.model;

public record ContentModerationCheckInput(
        String requestId,
        long userId,
        String userEmail,
        long apiKeyId,
        String apiKeyName,
        Long groupId,
        String groupName,
        String endpoint,
        String provider,
        String model,
        String protocol,
        byte[] body
) {
}
