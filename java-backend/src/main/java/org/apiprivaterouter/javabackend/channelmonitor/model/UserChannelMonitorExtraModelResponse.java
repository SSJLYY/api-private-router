package org.apiprivaterouter.javabackend.channelmonitor.model;

public record UserChannelMonitorExtraModelResponse(
        String model,
        String status,
        Integer latency_ms
) {
}
