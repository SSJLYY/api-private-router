package org.apiprivaterouter.javabackend.channelmonitor.model;

public record ChannelMonitorAvailabilityStat(
        long monitorId,
        String model,
        int windowDays,
        int totalChecks,
        int operationalChecks,
        double availabilityPct,
        Integer avgLatencyMs
) {
}
