package org.apiprivaterouter.javabackend.riskcontrol.runtime.model;

import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationApiKeyStatus;

import java.time.Instant;
import java.util.List;

public record ContentModerationRuntimeStatus(
        boolean enabled,
        boolean riskControlEnabled,
        String mode,
        int workerCount,
        int maxWorkers,
        int activeWorkers,
        int idleWorkers,
        int queueSize,
        int queueLength,
        double queueUsagePercent,
        long enqueued,
        long dropped,
        long processed,
        long errors,
        List<ContentModerationApiKeyStatus> apiKeyStatuses,
        long flaggedHashCount,
        Instant lastCleanupAt,
        long lastCleanupDeletedHit,
        long lastCleanupDeletedNonHit
) {
}
