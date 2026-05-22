package org.apiprivaterouter.javabackend.admin.dashboard.model;

public record UserUsageTrendPointResponse(
        String date,
        long user_id,
        String email,
        String username,
        long requests,
        long tokens,
        double cost,
        double actual_cost
) {
}
