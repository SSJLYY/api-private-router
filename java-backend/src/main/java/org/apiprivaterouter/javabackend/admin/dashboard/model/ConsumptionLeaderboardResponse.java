package org.apiprivaterouter.javabackend.admin.dashboard.model;

public record ConsumptionLeaderboardResponse(
        UserSpendingRankingResponse daily,
        UserSpendingRankingResponse weekly,
        UserSpendingRankingResponse monthly,
        UserSpendingRankingResponse yearly_best_employee,
        AccountConsumptionRankingResponse account_daily,
        AccountConsumptionRankingResponse account_weekly,
        AccountConsumptionRankingResponse account_monthly,
        AccountConsumptionRankingResponse account_yearly_best_employee
) {
}
