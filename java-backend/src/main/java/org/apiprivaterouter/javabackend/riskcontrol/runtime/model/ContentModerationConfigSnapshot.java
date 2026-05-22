package org.apiprivaterouter.javabackend.riskcontrol.runtime.model;

import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationApiKeyStatus;

import java.util.List;
import java.util.Map;

public record ContentModerationConfigSnapshot(
        boolean enabled,
        String mode,
        String baseUrl,
        String model,
        List<String> apiKeys,
        List<ContentModerationApiKeyStatus> apiKeyStatuses,
        int timeoutMs,
        int sampleRate,
        boolean allGroups,
        List<Long> groupIds,
        boolean recordNonHits,
        int workerCount,
        int queueSize,
        int blockStatus,
        String blockMessage,
        boolean emailOnHit,
        boolean autoBanEnabled,
        int banThreshold,
        int violationWindowHours,
        int retryCount,
        int hitRetentionDays,
        int nonHitRetentionDays,
        boolean preHashCheckEnabled,
        Map<String, Double> thresholds
) {

    public boolean includesGroup(Long groupId) {
        if (allGroups) {
            return true;
        }
        if (groupId == null || groupIds == null || groupIds.isEmpty()) {
            return false;
        }
        return groupIds.contains(groupId);
    }

    public boolean shouldSample(String hashText) {
        if (sampleRate >= 100) {
            return true;
        }
        if (sampleRate <= 0 || hashText == null || hashText.length() < 4) {
            return false;
        }
        try {
            int prefix = Integer.parseInt(hashText.substring(0, 4), 16);
            return prefix % 100 < sampleRate;
        } catch (NumberFormatException ex) {
            return true;
        }
    }
}
