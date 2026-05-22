package org.apiprivaterouter.javabackend.admin.dashboard.controller;

import org.apiprivaterouter.javabackend.admin.dashboard.model.AccountConsumptionRankingResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.ConsumptionLeaderboardResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.UserSpendingRankingResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.service.AdminDashboardService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/leaderboard")
public class PublicLeaderboardController {

    private final AdminDashboardService service;

    public PublicLeaderboardController(AdminDashboardService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<ConsumptionLeaderboardResponse> getConsumptionLeaderboard(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String timezone
    ) {
        return ApiResponse.success(service.getConsumptionLeaderboard(limit, timezone));
    }

    @GetMapping("/users-ranking")
    public ApiResponse<UserSpendingRankingResponse> getUserSpendingRanking(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String timezone
    ) {
        return ApiResponse.success(service.getUserSpendingRanking(startDate, endDate, limit, timezone));
    }

    @GetMapping("/accounts-ranking")
    public ApiResponse<AccountConsumptionRankingResponse> getAccountConsumptionRanking(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String timezone
    ) {
        return ApiResponse.success(service.getAccountConsumptionRanking(startDate, endDate, limit, timezone));
    }
}
