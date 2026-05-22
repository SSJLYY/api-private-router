package org.apiprivaterouter.javabackend.admin.usage.model;

public record UsageCleanupTaskResponse(
        long id,
        String status,
        Filters filters,
        long created_by,
        long deleted_rows,
        String error_message,
        Long canceled_by,
        String canceled_at,
        String started_at,
        String finished_at,
        String created_at,
        String updated_at
) {
    public record Filters(
            String start_time,
            String end_time,
            Long user_id,
            Long api_key_id,
            Long account_id,
            Long group_id,
            String model,
            String request_type,
            Boolean stream,
            Integer billing_type
    ) {
    }
}
