package org.apiprivaterouter.javabackend.channelmonitor.model;

import java.time.Instant;

public record UserMonitorTimelinePoint(
        String status,
        Integer latencyMs,
        Integer pingLatencyMs,
        Instant checkedAt
) {
}
