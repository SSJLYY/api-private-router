package org.apiprivaterouter.javabackend.channelmonitor.model;

import java.util.List;

public record ChannelMonitorSummary(
        String primaryStatus,
        Integer primaryLatencyMs,
        Integer primaryPingLatencyMs,
        double availability7d,
        List<ExtraModelStatus> extraModels
) {
}
