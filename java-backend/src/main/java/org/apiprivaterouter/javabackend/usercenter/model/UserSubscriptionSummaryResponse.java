package org.apiprivaterouter.javabackend.usercenter.model;

import java.util.List;

public record UserSubscriptionSummaryResponse(
        int active_count,
        double total_used_usd,
        List<Item> subscriptions
) {
    public record Item(
            long id,
            long group_id,
            String group_name,
            String status,
            double daily_used_usd,
            double daily_limit_usd,
            double weekly_used_usd,
            double weekly_limit_usd,
            double monthly_used_usd,
            double monthly_limit_usd,
            String expires_at,
            Double daily_progress,
            Double weekly_progress,
            Double monthly_progress,
            Integer days_remaining
    ) {
    }
}
