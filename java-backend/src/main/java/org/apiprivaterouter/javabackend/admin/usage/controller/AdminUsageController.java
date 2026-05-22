package org.apiprivaterouter.javabackend.admin.usage.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.usage.model.AdminUsageLogResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.AdminUsageStatsResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.CreateUsageCleanupTaskRequest;
import org.apiprivaterouter.javabackend.admin.usage.model.SimpleUsageApiKeyResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.SimpleUsageUserResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.UsageCleanupTaskCancelResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.UsageCleanupTaskResponse;
import org.apiprivaterouter.javabackend.admin.usage.service.AdminUsageService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/usage")
public class AdminUsageController {

    private final AdminUsageService service;
    private final CurrentUserContext currentUserContext;

    public AdminUsageController(AdminUsageService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminUsageLogResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "api_key_id", required = false) Long apiKeyId,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(required = false) String model,
            @RequestParam(name = "request_type", required = false) String requestType,
            @RequestParam(required = false) Boolean stream,
            @RequestParam(name = "billing_type", required = false) Integer billingType,
            @RequestParam(name = "billing_mode", required = false) String billingMode,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) String timezone,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listUsageLogs(
                page, pageSize, userId, apiKeyId, accountId, groupId, model, requestType, stream,
                billingType, billingMode, startDate, endDate, timezone, sortBy, sortOrder
        ));
    }

    @GetMapping("/stats")
    public ApiResponse<AdminUsageStatsResponse> stats(
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "api_key_id", required = false) Long apiKeyId,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(required = false) String model,
            @RequestParam(name = "request_type", required = false) String requestType,
            @RequestParam(required = false) Boolean stream,
            @RequestParam(required = false) String period,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) String timezone,
            @RequestParam(name = "billing_type", required = false) Integer billingType,
            @RequestParam(name = "billing_mode", required = false) String billingMode
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getStats(
                userId, apiKeyId, accountId, groupId, model, requestType, stream,
                period, startDate, endDate, timezone, billingType, billingMode
        ));
    }

    @GetMapping("/search-users")
    public ApiResponse<List<SimpleUsageUserResponse>> searchUsers(@RequestParam(name = "q", required = false) String keyword) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.searchUsers(keyword));
    }

    @GetMapping("/search-api-keys")
    public ApiResponse<List<SimpleUsageApiKeyResponse>> searchApiKeys(
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "q", required = false) String keyword
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.searchApiKeys(userId, keyword));
    }

    @GetMapping("/cleanup-tasks")
    public ApiResponse<PageResponse<UsageCleanupTaskResponse>> listCleanupTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listCleanupTasks(page, pageSize));
    }

    @PostMapping("/cleanup-tasks")
    public ApiResponse<UsageCleanupTaskResponse> createCleanupTask(@Valid @RequestBody CreateUsageCleanupTaskRequest request) {
        CurrentUser admin = currentUserContext.requireAdmin();
        return ApiResponse.success(service.createCleanupTask(request, admin.userId()));
    }

    @PostMapping("/cleanup-tasks/{id}/cancel")
    public ApiResponse<UsageCleanupTaskCancelResponse> cancelCleanupTask(@PathVariable long id) {
        CurrentUser admin = currentUserContext.requireAdmin();
        return ApiResponse.success(service.cancelCleanupTask(id, admin.userId()));
    }
}
