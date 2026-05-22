package org.apiprivaterouter.javabackend.admin.account.model;

public record ScheduledTestResultResponse(
        long id,
        long plan_id,
        String status,
        String response_text,
        String error_message,
        long latency_ms,
        String started_at,
        String finished_at,
        String created_at
) {
}
