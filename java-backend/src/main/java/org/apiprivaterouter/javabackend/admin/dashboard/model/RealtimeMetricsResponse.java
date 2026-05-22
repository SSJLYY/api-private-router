package org.apiprivaterouter.javabackend.admin.dashboard.model;

public record RealtimeMetricsResponse(
        long active_requests,
        long requests_per_minute,
        long average_response_time,
        double error_rate
) {
}
