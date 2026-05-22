package org.apiprivaterouter.javabackend.usercenter.model;

import java.util.List;

public record UserMonitorViewResponse(
        long id,
        String name,
        String provider,
        String group_name,
        String primary_model,
        String primary_status,
        Integer primary_latency_ms,
        Integer primary_ping_latency_ms,
        double availability_7d,
        List<UserMonitorExtraModelResponse> extra_models,
        List<MonitorTimelinePointResponse> timeline
) {
}
