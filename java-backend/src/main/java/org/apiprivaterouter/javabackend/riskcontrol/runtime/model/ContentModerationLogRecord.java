package org.apiprivaterouter.javabackend.riskcontrol.runtime.model;

import java.time.Instant;
import java.util.Map;

public record ContentModerationLogRecord(
        long id,
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
        int violationCount,
        boolean autoBanned,
        boolean emailSent,
        String userStatus,
        Integer queueDelayMs,
        Instant createdAt
) {
}
