package org.apiprivaterouter.javabackend.channelmonitor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Service
public class ChannelMonitorRunnerService {

    private static final Logger log = LoggerFactory.getLogger(ChannelMonitorRunnerService.class);
    private static final int MAX_WORKERS = 6;
    private static final int MAX_CLAIMS_PER_TICK = 24;
    private static final Executor COORDINATOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "api-private-router-channel-monitor-runner");
        thread.setDaemon(true);
        return thread;
    });

    private final ChannelMonitorCoreService coreService;
    private final Semaphore semaphore = new Semaphore(MAX_WORKERS);
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public ChannelMonitorRunnerService(ChannelMonitorCoreService coreService) {
        this.coreService = coreService;
    }

    @Scheduled(fixedDelay = 15000L, initialDelay = 15000L)
    public void scheduleTick() {
        COORDINATOR.execute(this::runScheduledOnce);
    }

    private void runScheduledOnce() {
        if (!coreService.isFeatureEnabled()) {
            return;
        }

        List<ChannelMonitorRecord> dueMonitors = coreService.claimDueMonitors(Instant.now(), MAX_CLAIMS_PER_TICK);
        if (dueMonitors.isEmpty()) {
            return;
        }
        log.info("channel monitor runner claimed {} due monitors", dueMonitors.size());

        for (ChannelMonitorRecord record : dueMonitors) {
            if (!inFlight.add(record.id())) {
                log.debug("channel monitor runner skipped duplicate in-flight monitor {}", record.id());
                continue;
            }
            try {
                semaphore.acquire();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); // Restore interrupt flag
                inFlight.remove(record.id());
                Thread.currentThread().interrupt();
                return;
            }
            Thread worker = new Thread(() -> {
                try {
                    coreService.runClaimedMonitor(record);
                } catch (Exception ex) {
                    log.warn("channel monitor runner failed for monitor {}", record.id(), ex);
                } finally {
                    inFlight.remove(record.id());
                    semaphore.release();
                }
            }, "channel-monitor-" + record.id());
            worker.setDaemon(true);
            worker.start();
        }
    }
}
