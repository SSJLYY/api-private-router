package org.apiprivaterouter.javabackend.admin.account.model;

public record AccountUsageHistoryResponse(
        String date,
        String label,
        long requests,
        long tokens,
        double cost,
        double actual_cost,
        double user_cost
) {
}
