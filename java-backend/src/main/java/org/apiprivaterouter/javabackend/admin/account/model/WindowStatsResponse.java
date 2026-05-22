package org.apiprivaterouter.javabackend.admin.account.model;

public record WindowStatsResponse(
        long requests,
        long tokens,
        double cost,
        double standard_cost,
        double user_cost
) {
}
