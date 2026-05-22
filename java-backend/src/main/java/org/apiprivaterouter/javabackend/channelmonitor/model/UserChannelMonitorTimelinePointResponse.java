package org.apiprivaterouter.javabackend.channelmonitor.model;

public record UserChannelMonitorTimelinePointResponse(
        String status,
        Integer latency_ms,
        Integer ping_latency_ms,
        String checked_at
) {
}
