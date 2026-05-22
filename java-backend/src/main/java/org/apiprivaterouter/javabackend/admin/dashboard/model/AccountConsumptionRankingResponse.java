package org.apiprivaterouter.javabackend.admin.dashboard.model;

import java.util.List;

public record AccountConsumptionRankingResponse(
        List<AccountConsumptionRankingItem> ranking,
        double total_account_cost,
        long total_requests,
        long total_tokens,
        String start_date,
        String end_date
) {
    public record AccountConsumptionRankingItem(
            long account_id,
            String account_name,
            String platform,
            double account_cost,
            double actual_cost,
            long requests,
            long tokens
    ) {
    }
}
