package org.apiprivaterouter.javabackend.channelmonitor.model;

public record UserMonitorModelDetail(
        String model,
        String latestStatus,
        Integer latestLatencyMs,
        double availability7d,
        double availability15d,
        double availability30d,
        Integer avgLatency7dMs
) {
}
