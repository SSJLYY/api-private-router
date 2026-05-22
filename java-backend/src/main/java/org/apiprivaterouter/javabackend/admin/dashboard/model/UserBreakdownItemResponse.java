package org.apiprivaterouter.javabackend.admin.dashboard.model;

public record UserBreakdownItemResponse(
        long user_id,
        String email,
        long requests,
        long total_tokens,
        double cost,
        double actual_cost,
        double account_cost
) {
}
