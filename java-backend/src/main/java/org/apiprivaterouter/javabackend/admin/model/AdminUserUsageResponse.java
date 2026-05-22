package org.apiprivaterouter.javabackend.admin.model;

public record AdminUserUsageResponse(
        long total_requests,
        double total_cost,
        long total_tokens
) {
}
