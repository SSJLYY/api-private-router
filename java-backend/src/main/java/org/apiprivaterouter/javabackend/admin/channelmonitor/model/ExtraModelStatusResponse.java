package org.apiprivaterouter.javabackend.admin.channelmonitor.model;

public record ExtraModelStatusResponse(
        String model,
        String status,
        Integer latency_ms
) {
}
