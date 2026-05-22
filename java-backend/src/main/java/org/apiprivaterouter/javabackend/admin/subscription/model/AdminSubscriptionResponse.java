package org.apiprivaterouter.javabackend.admin.subscription.model;

public record AdminSubscriptionResponse(
        long id,
        long user_id,
        long group_id,
        String starts_at,
        String expires_at,
        String status,
        double daily_usage_usd,
        double weekly_usage_usd,
        double monthly_usage_usd,
        String daily_window_start,
        String weekly_window_start,
        String monthly_window_start,
        Long assigned_by,
        String assigned_at,
        String notes,
        String created_at,
        String updated_at,
        UserSummary user,
        GroupSummary group,
        UserSummary assigned_by_user
) {
    public record UserSummary(
            long id,
            String email,
            String username
    ) {
    }

    public record GroupSummary(
            long id,
            String name,
            String description,
            String platform,
            Double rate_multiplier,
            String status,
            String subscription_type,
            Double daily_limit_usd,
            Double weekly_limit_usd,
            Double monthly_limit_usd
    ) {
    }
}
