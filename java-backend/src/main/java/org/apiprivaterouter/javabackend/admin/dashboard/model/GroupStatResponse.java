package org.apiprivaterouter.javabackend.admin.dashboard.model;

public record GroupStatResponse(
        long group_id,
        String group_name,
        long requests,
        long total_tokens,
        double cost,
        double actual_cost,
        double account_cost
) {
}
