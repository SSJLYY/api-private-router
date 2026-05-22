package org.apiprivaterouter.javabackend.gateway.runtime.model;

public record GatewaySubscriptionSummary(
        long id,
        long userId,
        long groupId,
        String status,
        String expiresAt,
        double dailyUsageUsd,
        double weeklyUsageUsd,
        double monthlyUsageUsd,
        String dailyWindowStart,
        String weeklyWindowStart,
        String monthlyWindowStart
) {
}
