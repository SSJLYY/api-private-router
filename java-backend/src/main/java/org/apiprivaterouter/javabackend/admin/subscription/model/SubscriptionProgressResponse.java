package org.apiprivaterouter.javabackend.admin.subscription.model;

public record SubscriptionProgressResponse(
        long subscription_id,
        WindowProgress daily,
        WindowProgress weekly,
        WindowProgress monthly,
        String expires_at,
        Integer days_remaining
) {
    public record WindowProgress(
            double used,
            Double limit,
            double percentage,
            Long reset_in_seconds
    ) {
    }
}
