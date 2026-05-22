package org.apiprivaterouter.javabackend.admin.usage.model;

import jakarta.validation.constraints.Size;

public record CreateUsageCleanupTaskRequest(
        @Size(max = 32) String start_date,
        @Size(max = 32) String end_date,
        Long user_id,
        Long api_key_id,
        Long account_id,
        Long group_id,
        String model,
        String request_type,
        Boolean stream,
        Integer billing_type,
        String timezone
) {
}
