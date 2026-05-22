package org.apiprivaterouter.javabackend.admin.dashboard.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.dashboard.model.AdminDashboardStatsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.ApiKeyTrendResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.BatchApiKeysUsageRequest;
import org.apiprivaterouter.javabackend.admin.dashboard.model.BatchApiKeysUsageResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.BatchUsersUsageRequest;
import org.apiprivaterouter.javabackend.admin.dashboard.model.BatchUsersUsageResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.DashboardAggregationBackfillRequest;
import org.apiprivaterouter.javabackend.admin.dashboard.model.DashboardSnapshotV2Response;
import org.apiprivaterouter.javabackend.admin.dashboard.model.GroupStatsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.ModelStatsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.RealtimeMetricsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.TrendResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.UserBreakdownResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.UserSpendingRankingResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.UserTrendResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.service.AdminDashboardService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService service;
    private final CurrentUserContext currentUserContext;

    public AdminDashboardController(AdminDashboardService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/stats")
    public ApiResponse<AdminDashboardStatsResponse> getStats() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getStats());
    }

    @GetMapping("/realtime")
    public ApiResponse<RealtimeMetricsResponse> getRealtimeMetrics() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getRealtimeMetrics());
    }

    @GetMapping("/trend")
    public ApiResponse<TrendResponse> getUsageTrend(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) String granularity,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "api_key_id", required = false) Long apiKeyId,
            @RequestParam(required = false) String model,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(name = "request_type", required = false) String requestType,
            @RequestParam(required = false) Boolean stream,
            @RequestParam(name = "billing_type", required = false) Integer billingType,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getUsageTrend(
                startDate, endDate, granularity, userId, apiKeyId, model, accountId, groupId, requestType, stream, billingType, timezone
        ));
    }

    @GetMapping("/models")
    public ApiResponse<ModelStatsResponse> getModelStats(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "api_key_id", required = false) Long apiKeyId,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(name = "request_type", required = false) String requestType,
            @RequestParam(required = false) Boolean stream,
            @RequestParam(name = "billing_type", required = false) Integer billingType,
            @RequestParam(name = "model_source", required = false) String modelSource,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getModelStats(
                startDate, endDate, userId, apiKeyId, accountId, groupId, requestType, stream, billingType, modelSource, timezone
        ));
    }

    @GetMapping("/groups")
    public ApiResponse<GroupStatsResponse> getGroupStats(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "api_key_id", required = false) Long apiKeyId,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(name = "request_type", required = false) String requestType,
            @RequestParam(required = false) Boolean stream,
            @RequestParam(name = "billing_type", required = false) Integer billingType,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getGroupStats(
                startDate, endDate, userId, apiKeyId, accountId, groupId, requestType, stream, billingType, timezone
        ));
    }

    @GetMapping("/users-trend")
    public ApiResponse<UserTrendResponse> getUserUsageTrend(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getUserUsageTrend(startDate, endDate, granularity, limit, timezone));
    }

    @GetMapping("/api-keys-trend")
    public ApiResponse<ApiKeyTrendResponse> getApiKeyUsageTrend(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getApiKeyUsageTrend(startDate, endDate, granularity, limit, timezone));
    }

    @GetMapping("/user-breakdown")
    public ApiResponse<UserBreakdownResponse> getUserBreakdown(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(required = false) String model,
            @RequestParam(name = "model_source", required = false) String modelSource,
            @RequestParam(required = false) String endpoint,
            @RequestParam(name = "endpoint_type", required = false) String endpointType,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "api_key_id", required = false) Long apiKeyId,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "request_type", required = false) String requestType,
            @RequestParam(required = false) Boolean stream,
            @RequestParam(name = "billing_type", required = false) Integer billingType,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getUserBreakdown(
                startDate, endDate, groupId, model, modelSource, endpoint, endpointType,
                userId, apiKeyId, accountId, requestType, stream, billingType, limit, timezone
        ));
    }

    @GetMapping("/snapshot-v2")
    public ApiResponse<DashboardSnapshotV2Response> getSnapshotV2(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) String granularity,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "api_key_id", required = false) Long apiKeyId,
            @RequestParam(required = false) String model,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(name = "request_type", required = false) String requestType,
            @RequestParam(required = false) Boolean stream,
            @RequestParam(name = "billing_type", required = false) Integer billingType,
            @RequestParam(name = "include_stats", required = false) Boolean includeStats,
            @RequestParam(name = "include_trend", required = false) Boolean includeTrend,
            @RequestParam(name = "include_model_stats", required = false) Boolean includeModelStats,
            @RequestParam(name = "include_group_stats", required = false) Boolean includeGroupStats,
            @RequestParam(name = "include_users_trend", required = false) Boolean includeUsersTrend,
            @RequestParam(name = "users_trend_limit", required = false) Integer usersTrendLimit,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getSnapshotV2(
                startDate, endDate, granularity, userId, apiKeyId, model, accountId, groupId,
                requestType, stream, billingType, includeStats, includeTrend, includeModelStats,
                includeGroupStats, includeUsersTrend, usersTrendLimit, timezone
        ));
    }

    @GetMapping("/users-ranking")
    public ApiResponse<UserSpendingRankingResponse> getUserSpendingRanking(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getUserSpendingRanking(startDate, endDate, limit, timezone));
    }

    @PostMapping("/users-usage")
    public ApiResponse<BatchUsersUsageResponse> getBatchUsersUsage(
            @Valid @RequestBody BatchUsersUsageRequest request,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getBatchUsersUsage(request.user_ids(), timezone));
    }

    @PostMapping("/api-keys-usage")
    public ApiResponse<BatchApiKeysUsageResponse> getBatchApiKeysUsage(
            @Valid @RequestBody BatchApiKeysUsageRequest request,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getBatchApiKeysUsage(request.api_key_ids(), timezone));
    }

    @PostMapping("/aggregation/backfill")
    public ApiResponse<Map<String, String>> backfillAggregation(@Valid @RequestBody DashboardAggregationBackfillRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.backfillAggregation(request));
    }
}
