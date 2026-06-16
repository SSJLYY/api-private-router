package org.apiprivaterouter.javabackend.gateway.service.openai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class OpenAiAccountRuntimeBlockFastpath {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAccountRuntimeBlockFastpath.class);
    private final ConcurrentMap<Long, Instant> blockUntilMap = new ConcurrentHashMap<>();
    private final SlidingWindowCounter oauth429Counter = new SlidingWindowCounter(10_000, 20);

    public void blockAccountScheduling(long accountId, Instant until, String reason) {
        blockUntilMap.compute(accountId, (id, existing) -> {
            if (existing == null || until.isAfter(existing)) {
                log.debug("blocking account {} until {} reason={}", accountId, until, reason);
                return until;
            }
            return existing;
        });
    }

    public void clearAccountSchedulingBlock(long accountId) {
        blockUntilMap.remove(accountId);
    }

    public boolean isAccountRuntimeBlocked(long accountId) {
        Instant deadline = blockUntilMap.get(accountId);
        if (deadline == null) {
            return false;
        }
        if (Instant.now().isAfter(deadline)) {
            blockUntilMap.remove(accountId);
            return false;
        }
        return true;
    }

    public void markOAuth429RateLimited(long accountId) {
        oauth429Counter.increment();
        blockAccountScheduling(accountId, Instant.now().plusSeconds(5), "oauth_429");
    }

    public boolean shouldStopOAuth429Failover() {
        return oauth429Counter.isStorm();
    }

    private static class SlidingWindowCounter {
        private final long windowMs;
        private final int threshold;
        private final AtomicInteger count = new AtomicInteger(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

        SlidingWindowCounter(long windowMs, int threshold) {
            this.windowMs = windowMs;
            this.threshold = threshold;
        }

        void increment() {
            long now = System.currentTimeMillis();
            long start = windowStart.get();
            if (now - start > windowMs) {
                windowStart.compareAndSet(start, now);
                count.set(1);
            } else {
                count.incrementAndGet();
            }
        }

        boolean isStorm() {
            long now = System.currentTimeMillis();
            long start = windowStart.get();
            if (now - start > windowMs) {
                return false;
            }
            return count.get() >= threshold;
        }
    }
}
