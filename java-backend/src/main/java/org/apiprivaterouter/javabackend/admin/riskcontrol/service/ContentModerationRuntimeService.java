package org.apiprivaterouter.javabackend.admin.riskcontrol.service;

import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationRuntimeStatusResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.repository.ContentModerationHashRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ContentModerationRuntimeService {

    private final ContentModerationApiKeyHealthTracker apiKeyHealthTracker;
    private final ContentModerationHashRepository hashRepository;
    private final AtomicInteger configuredWorkerCount = new AtomicInteger(ContentModerationSupport.DEFAULT_WORKER_COUNT);
    private final AtomicInteger configuredQueueSize = new AtomicInteger(ContentModerationSupport.DEFAULT_QUEUE_SIZE);
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicInteger queueLength = new AtomicInteger();
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile CleanupSnapshot cleanupSnapshot = CleanupSnapshot.empty();

    public ContentModerationRuntimeService(
            ContentModerationApiKeyHealthTracker apiKeyHealthTracker,
            ContentModerationHashRepository hashRepository
    ) {
        this.apiKeyHealthTracker = apiKeyHealthTracker;
        this.hashRepository = hashRepository;
    }

    public void syncConfig(ContentModerationLoadedConfig config) {
        if (config == null) {
            return;
        }
        configuredWorkerCount.set(config.response().worker_count());
        configuredQueueSize.set(config.response().queue_size());
    }

    public void recordEnqueued() {
        enqueued.incrementAndGet();
        queueLength.incrementAndGet();
    }

    public void recordDequeued() {
        queueLength.updateAndGet(value -> Math.max(0, value - 1));
    }

    public void recordDropped() {
        dropped.incrementAndGet();
    }

    public void recordProcessingStarted() {
        activeWorkers.incrementAndGet();
    }

    public void recordProcessingCompleted(boolean error) {
        activeWorkers.updateAndGet(value -> Math.max(0, value - 1));
        processed.incrementAndGet();
        if (error) {
            errors.incrementAndGet();
        }
    }

    public void updateCleanupSnapshot(Instant finishedAt, long deletedHit, long deletedNonHit) {
        cleanupSnapshot = new CleanupSnapshot(finishedAt, deletedHit, deletedNonHit);
    }

    public ContentModerationRuntimeStatusResponse buildStatus(
            ContentModerationLoadedConfig config,
            boolean riskControlEnabled
    ) {
        syncConfig(config);
        int workerCount = configuredWorkerCount.get();
        int active = Math.max(0, Math.min(activeWorkers.get(), workerCount));
        int idle = Math.max(0, workerCount - active);
        int queueSize = configuredQueueSize.get();
        int currentQueueLength = Math.max(0, queueLength.get());
        double queueUsagePercent = queueSize <= 0 ? 0D : Math.min(100D, (currentQueueLength * 100D) / queueSize);
        List<org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationApiKeyStatus> apiKeyStatuses =
                apiKeyHealthTracker.buildStatuses(config.apiKeys(), true);
        CleanupSnapshot cleanup = cleanupSnapshot;

        return new ContentModerationRuntimeStatusResponse(
                config.response().enabled(),
                riskControlEnabled,
                config.response().mode(),
                workerCount,
                ContentModerationSupport.MAX_WORKER_COUNT,
                active,
                idle,
                queueSize,
                currentQueueLength,
                queueUsagePercent,
                enqueued.get(),
                dropped.get(),
                processed.get(),
                errors.get(),
                apiKeyStatuses,
                hashRepository.count(),
                cleanup.finishedAt() == null ? null : cleanup.finishedAt().toString(),
                cleanup.deletedHit(),
                cleanup.deletedNonHit()
        );
    }

    private record CleanupSnapshot(Instant finishedAt, long deletedHit, long deletedNonHit) {
        private static CleanupSnapshot empty() {
            return new CleanupSnapshot(null, 0, 0);
        }
    }
}
