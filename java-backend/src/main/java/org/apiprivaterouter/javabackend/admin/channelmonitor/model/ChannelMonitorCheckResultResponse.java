package org.apiprivaterouter.javabackend.admin.channelmonitor.model;

public record ChannelMonitorCheckResultResponse(
        String model,
        String status,
        Integer latency_ms,
        Integer ping_latency_ms,
        String message,
        String checked_at
) {
}
