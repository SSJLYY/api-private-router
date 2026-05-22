package org.apiprivaterouter.javabackend.channelmonitor.model;

import java.time.Instant;

public record ChannelMonitorCheckResult(
        String model,
        String status,
        Integer latencyMs,
        Integer pingLatencyMs,
        String message,
        Instant checkedAt
) {
}
