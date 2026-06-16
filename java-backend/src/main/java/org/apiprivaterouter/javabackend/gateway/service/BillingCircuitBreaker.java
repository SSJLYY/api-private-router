package org.apiprivaterouter.javabackend.gateway.service;

import org.apiprivaterouter.javabackend.common.config.BillingCircuitBreakerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class BillingCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(BillingCircuitBreaker.class);

    enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final boolean enabled;
    private final int failureThreshold;
    private final Duration resetTimeout;
    private final int halfOpenMaxRequests;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);
    private final AtomicInteger halfOpenRemaining = new AtomicInteger(0);

    public BillingCircuitBreaker(BillingCircuitBreakerProperties properties) {
        this.enabled = properties.enabled();
        this.failureThreshold = properties.failureThreshold();
        this.resetTimeout = Duration.ofSeconds(properties.resetTimeoutSeconds());
        this.halfOpenMaxRequests = properties.halfOpenRequests();
    }

    public boolean allow() {
        if (!enabled) {
            return true;
        }

        State current = state.get();
        switch (current) {
            case CLOSED -> {
                return true;
            }
            case OPEN -> {
                long opened = openedAt.get();
                Duration elapsed = Duration.between(Instant.ofEpochMilli(opened), Instant.now());
                if (elapsed.compareTo(resetTimeout) < 0) {
                    return false;
                }
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenRemaining.set(halfOpenMaxRequests);
                    log.warn("ALERT: billing circuit breaker entering half-open state");
                }
            }
            case HALF_OPEN -> {
                int remaining = halfOpenRemaining.decrementAndGet();
                if (remaining < 0) {
                    halfOpenRemaining.set(0);
                    return false;
                }
                return true;
            }
        }
        return state.get() == State.HALF_OPEN && halfOpenRemaining.get() > 0;
    }

    public void onFailure(Exception err) {
        if (!enabled) {
            return;
        }

        State current = state.get();
        switch (current) {
            case OPEN -> {
                return;
            }
            case HALF_OPEN -> {
                state.set(State.OPEN);
                openedAt.set(Instant.now().toEpochMilli());
                halfOpenRemaining.set(0);
                log.warn("ALERT: billing circuit breaker opened after half-open failure: {}", err.getMessage());
            }
            case CLOSED -> {
                int count = failures.incrementAndGet();
                if (count >= failureThreshold) {
                    state.set(State.OPEN);
                    openedAt.set(Instant.now().toEpochMilli());
                    halfOpenRemaining.set(0);
                    log.warn("ALERT: billing circuit breaker opened after {} failures: {}", count, err.getMessage());
                }
            }
        }
    }

    public void onSuccess() {
        if (!enabled) {
            return;
        }

        State previous = state.getAndSet(State.CLOSED);
        int previousFailures = failures.getAndSet(0);
        halfOpenRemaining.set(0);

        if (previous != State.CLOSED) {
            log.warn("ALERT: billing circuit breaker closed (was {})", previous);
        } else if (previousFailures > 0) {
            log.info("INFO: billing circuit breaker failures reset from {}", previousFailures);
        }
    }

    public State getState() {
        return enabled ? state.get() : State.CLOSED;
    }

    public int getFailureCount() {
        return enabled ? failures.get() : 0;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
