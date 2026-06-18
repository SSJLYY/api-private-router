package org.apiprivaterouter.javabackend.userusage.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.dashboard.model.BatchApiKeysUsageResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.ModelStatsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.TrendResponse;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.userusage.model.BatchApiKeysUsageRequest;
import org.apiprivaterouter.javabackend.userusage.model.UserDashboardStatsResponse;
import org.apiprivaterouter.javabackend.userusage.model.UserErrorDetailResponse;
import org.apiprivaterouter.javabackend.userusage.model.UserErrorLogResponse;
import org.apiprivaterouter.javabackend.userusage.model.UserUsageLogResponse;
import org.apiprivaterouter.javabackend.userusage.model.UserUsageStatsResponse;
import org.apiprivaterouter.javabackend.userusage.service.UserUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
public class UserUsageController {

    private final UserUsageService service;
    private final CurrentUserContext currentUserContext;

    public UserUsageController(UserUsageService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<PageResponse<UserUsageLogResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(name = "api_key_id", required = false) Long apiKeyId,
            @RequestParam(required = false) String model,
            @RequestParam(name = "request_type", required = false) String requestType,
            @RequestParam(required = false) Boolean stream,
            @RequestParam(name = "billing_type", required = false) Integer billingType,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) String timezone,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        return ApiResponse.success(service.listUsageLogs(
                currentUserContext.requireUser(),
                page,
                pageSize,
                apiKeyId,
                model,
                requestType,
                stream,
                billingType,
                startDate,
                endDate,
                timezone,
                sortBy,
                sortOrder
        ));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserUsageLogResponse> getById(@PathVariable long id) {
        return ApiResponse.success(service.getById(currentUserContext.requireUser(), id));
    }

    @GetMapping("/stats")
    public ApiResponse<UserUsageStatsResponse> stats(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(name = "api_key_id", required = false) Long apiKeyId,
            @RequestParam(required = false) String model,
            @RequestParam(name = "request_type", required = false) String requestType,
            @RequestParam(required = false) Boolean stream,
            @RequestParam(name = "billing_type", required = false) Integer billingType,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) String timezone
    ) {
        return ApiResponse.success(service.getStats(
                currentUserContext.requireUser(),
                period,
                apiKeyId,
                model,
                requestType,
                stream,
                billingType,
                startDate,
                endDate,
                timezone
        ));
    }

    @GetMapping("/dashboard/stats")
    public ApiResponse<UserDashboardStatsResponse> dashboardStats() {
        return ApiResponse.success(service.getDashboardStats(currentUserContext.requireUser()));
    }

    @GetMapping("/dashboard/trend")
    public ApiResponse<TrendResponse> dashboardTrend(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String timezone
    ) {
        return ApiResponse.success(service.getTrend(
                currentUserContext.requireUser(),
                startDate,
                endDate,
                granularity,
                timezone
        ));
    }

    @GetMapping("/dashboard/models")
    public ApiResponse<ModelStatsResponse> dashboardModels(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) String timezone
    ) {
        return ApiResponse.success(service.getModelStats(
                currentUserContext.requireUser(),
                startDate,
                endDate,
                timezone
        ));
    }

    @GetMapping("/errors")
    public ApiResponse<PageResponse<UserErrorLogResponse>> listErrors(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(name = "api_key_id", required = false) Long apiKeyId,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) String timezone
    ) {
        return ApiResponse.success(service.listErrorLogs(
                currentUserContext.requireUser(),
                page,
                pageSize,
                apiKeyId,
                startDate,
                endDate,
                timezone
        ));
    }

    @GetMapping("/errors/{id}")
    public ApiResponse<UserErrorDetailResponse> getErrorDetail(@PathVariable long id) {
        return ApiResponse.success(service.getErrorDetail(currentUserContext.requireUser(), id));
    }

    @PostMapping("/dashboard/api-keys-usage")
    public ApiResponse<BatchApiKeysUsageResponse> batchApiKeysUsage(
            @Valid @RequestBody BatchApiKeysUsageRequest request,
            @RequestParam(required = false) String timezone
    ) {
        return ApiResponse.success(service.getBatchApiKeysUsage(
                currentUserContext.requireUser(),
                request,
                timezone
        ));
    }
}
