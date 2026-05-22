package org.apiprivaterouter.javabackend.channelmonitor.model;

import java.time.Instant;

public record ChannelMonitorLatestStatus(
        long monitorId,
        String model,
        String status,
        Integer latencyMs,
        Integer pingLatencyMs,
        Instant checkedAt
) {
}
