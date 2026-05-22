package org.apiprivaterouter.javabackend.admin.riskcontrol.service;

import java.util.Map;

public record ContentModerationLogCommand(
        String requestId,
        Long userId,
        String userEmail,
        Long apiKeyId,
        String apiKeyName,
        Long groupId,
        String groupName,
        String endpoint,
        String provider,
        String model,
        String mode,
        String action,
        boolean flagged,
        String highestCategory,
        double highestScore,
        Map<String, Double> categoryScores,
        Map<String, Double> thresholdSnapshot,
        String inputExcerpt,
        Integer upstreamLatencyMs,
        String error,
        Integer queueDelayMs,
        boolean emailSent
) {
}
