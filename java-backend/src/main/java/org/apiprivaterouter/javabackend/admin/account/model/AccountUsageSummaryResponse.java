package org.apiprivaterouter.javabackend.admin.account.model;

public record AccountUsageSummaryResponse(
        int days,
        int actual_days_used,
        double total_cost,
        double total_user_cost,
        double total_standard_cost,
        long total_requests,
        long total_tokens,
        double avg_daily_cost,
        double avg_daily_user_cost,
        double avg_daily_requests,
        double avg_daily_tokens,
        double avg_duration_ms,
        SummaryDay today,
        SummaryPeakDay highest_cost_day,
        SummaryPeakDay highest_request_day
) {
    public record SummaryDay(
            String date,
            double cost,
            double user_cost,
            long requests,
            long tokens
    ) {
    }

    public record SummaryPeakDay(
            String date,
            String label,
            double cost,
            double user_cost,
            long requests
    ) {
    }
}
