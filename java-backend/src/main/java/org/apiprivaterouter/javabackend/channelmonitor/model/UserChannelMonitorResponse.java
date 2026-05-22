package org.apiprivaterouter.javabackend.channelmonitor.model;

import java.util.List;

public record UserChannelMonitorResponse(
        long id,
        String name,
        String provider,
        String group_name,
        String primary_model,
        String primary_status,
        Integer primary_latency_ms,
        Integer primary_ping_latency_ms,
        double availability_7d,
        List<UserChannelMonitorExtraModelResponse> extra_models,
        List<UserChannelMonitorTimelinePointResponse> timeline
) {
}
