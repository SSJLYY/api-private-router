package org.apiprivaterouter.javabackend.usercenter.model;

public record MonitorTimelinePointResponse(
        String status,
        Integer latency_ms,
        Integer ping_latency_ms,
        String checked_at
) {
}
