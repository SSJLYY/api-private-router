package org.apiprivaterouter.javabackend.channelmonitor.model;

public record ExtraModelStatus(
        String model,
        String status,
        Integer latencyMs
) {
}
