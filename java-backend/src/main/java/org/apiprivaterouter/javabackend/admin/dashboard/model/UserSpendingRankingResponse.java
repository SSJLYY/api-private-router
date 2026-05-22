package org.apiprivaterouter.javabackend.admin.dashboard.model;

import java.util.List;

public record UserSpendingRankingResponse(
        List<UserSpendingRankingItem> ranking,
        double total_actual_cost,
        long total_requests,
        long total_tokens,
        String start_date,
        String end_date
) {
    public record UserSpendingRankingItem(
            long user_id,
            String email,
            double actual_cost,
            long requests,
            long tokens
    ) {
    }
}
