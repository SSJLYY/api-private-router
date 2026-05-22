package org.apiprivaterouter.javabackend.admin.subscription.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.subscription.model.AdminSubscriptionResponse;
import org.apiprivaterouter.javabackend.admin.subscription.model.AssignSubscriptionRequest;
import org.apiprivaterouter.javabackend.admin.subscription.model.BulkAssignSubscriptionRequest;
import org.apiprivaterouter.javabackend.admin.subscription.model.BulkAssignSubscriptionResponse;
import org.apiprivaterouter.javabackend.admin.subscription.model.ExtendSubscriptionRequest;
import org.apiprivaterouter.javabackend.admin.subscription.model.ResetSubscriptionQuotaRequest;
import org.apiprivaterouter.javabackend.admin.subscription.model.SubscriptionProgressResponse;
import org.apiprivaterouter.javabackend.admin.subscription.service.AdminSubscriptionService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminSubscriptionController {

    private final AdminSubscriptionService service;
    private final CurrentUserContext currentUserContext;

    public AdminSubscriptionController(AdminSubscriptionService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/subscriptions")
    public ApiResponse<PageResponse<AdminSubscriptionResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String platform,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listSubscriptions(page, pageSize, userId, groupId, status, platform, sortBy, sortOrder));
    }

    @GetMapping("/subscriptions/{id}")
    public ApiResponse<AdminSubscriptionResponse> getById(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getSubscription(id));
    }

    @GetMapping("/subscriptions/{id}/progress")
    public ApiResponse<SubscriptionProgressResponse> getProgress(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getProgress(id));
    }

    @PostMapping("/subscriptions/assign")
    public ApiResponse<AdminSubscriptionResponse> assign(@Valid @RequestBody AssignSubscriptionRequest request) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        return ApiResponse.success(service.assignSubscription(request, currentUser.userId()));
    }

    @PostMapping("/subscriptions/bulk-assign")
    public ApiResponse<BulkAssignSubscriptionResponse> bulkAssign(@Valid @RequestBody BulkAssignSubscriptionRequest request) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        return ApiResponse.success(service.bulkAssignSubscriptions(request, currentUser.userId()));
    }

    @PostMapping("/subscriptions/{id}/extend")
    public ApiResponse<AdminSubscriptionResponse> extend(@PathVariable long id, @Valid @RequestBody ExtendSubscriptionRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.extendSubscription(id, request));
    }

    @PostMapping("/subscriptions/{id}/reset-quota")
    public ApiResponse<AdminSubscriptionResponse> resetQuota(@PathVariable long id, @RequestBody ResetSubscriptionQuotaRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.resetQuota(id, request));
    }

    @DeleteMapping("/subscriptions/{id}")
    public ApiResponse<Map<String, String>> revoke(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.revokeSubscription(id));
    }

    @GetMapping("/groups/{id}/subscriptions")
    public ApiResponse<PageResponse<AdminSubscriptionResponse>> listByGroup(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listGroupSubscriptions(id, page, pageSize));
    }

    @GetMapping("/users/{id}/subscriptions")
    public ApiResponse<PageResponse<AdminSubscriptionResponse>> listByUser(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listUserSubscriptions(id, page, pageSize));
    }
}
