package org.apiprivaterouter.javabackend.channelmonitor.model;

import java.util.List;

public record UserMonitorViewRecord(
        long id,
        String name,
        String provider,
        String groupName,
        String primaryModel,
        String primaryStatus,
        Integer primaryLatencyMs,
        Integer primaryPingLatencyMs,
        double availability7d,
        List<ExtraModelStatus> extraModels,
        List<UserMonitorTimelinePoint> timeline
) {
}
