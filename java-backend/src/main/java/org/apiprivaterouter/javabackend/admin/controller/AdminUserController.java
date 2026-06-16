package org.apiprivaterouter.javabackend.admin.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.model.AdminUserResponse;
import org.apiprivaterouter.javabackend.admin.model.AdminUserUsageResponse;
import org.apiprivaterouter.javabackend.admin.model.AdminBoundAuthIdentityResponse;
import org.apiprivaterouter.javabackend.admin.model.AdminUserBalanceHistoryResponse;
import org.apiprivaterouter.javabackend.admin.model.AdminUserRpmStatusResponse;
import org.apiprivaterouter.javabackend.admin.model.BatchUpdateUserConcurrencyRequest;
import org.apiprivaterouter.javabackend.admin.model.BindUserAuthIdentityRequest;
import org.apiprivaterouter.javabackend.admin.model.CreateAdminUserRequest;
import org.apiprivaterouter.javabackend.admin.model.ReplaceUserGroupRequest;
import org.apiprivaterouter.javabackend.admin.model.UpdateAdminUserRequest;
import org.apiprivaterouter.javabackend.admin.model.UpdateBalanceRequest;
import org.apiprivaterouter.javabackend.admin.service.AdminUserService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final CurrentUserContext currentUserContext;

    public AdminUserController(AdminUserService adminUserService, CurrentUserContext currentUserContext) {
        this.adminUserService = adminUserService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminUserResponse>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String search,
            @RequestParam(name = "group_name", required = false) String groupName,
            @RequestParam(name = "api_key_group", required = false) Long apiKeyGroup,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(adminUserService.listUsers(page, pageSize, status, role, search, groupName, apiKeyGroup, sortBy, sortOrder));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminUserResponse> getUser(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(adminUserService.getUser(id));
    }

    @PostMapping
    public ApiResponse<AdminUserResponse> createUser(@Valid @RequestBody CreateAdminUserRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(adminUserService.createUser(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminUserResponse> updateUser(@PathVariable long id, @Valid @RequestBody UpdateAdminUserRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(adminUserService.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> deleteUser(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(adminUserService.deleteUser(id));
    }

    @PostMapping("/{id}/balance")
    public ApiResponse<AdminUserResponse> updateBalance(@PathVariable long id, @Valid @RequestBody UpdateBalanceRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(adminUserService.updateBalance(id, request.balance(), request.operation(), request.notes()));
    }

    @GetMapping("/{id}/api-keys")
    public ApiResponse<PageResponse<Map<String, Object>>> getUserApiKeys(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(adminUserService.getUserApiKeys(id, page, pageSize));
    }

    @GetMapping("/{id}/usage")
    public ApiResponse<AdminUserUsageResponse> getUserUsage(
            @PathVariable long id,
            @RequestParam(defaultValue = "month") String period
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(adminUserService.getUserUsage(id, period));
    }

    @GetMapping("/{id}/balance-history")
    public ApiResponse<AdminUserBalanceHistoryResponse> getUserBalanceHistory(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String type
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(adminUserService.getUserBalanceHistory(id, page, pageSize, type));
    }

    @GetMapping("/{id}/rpm-status")
    public ApiResponse<AdminUserRpmStatusResponse> getUserRpmStatus(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(adminUserService.getUserRpmStatus(id));
    }

    @PostMapping("/batch-concurrency")
    public ApiResponse<Map<String, Integer>> batchUpdateConcurrency(
            @Valid @RequestBody BatchUpdateUserConcurrencyRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(Map.of(
                "affected",
                adminUserService.batchUpdateConcurrency(
                        request.user_ids(),
                        Boolean.TRUE.equals(request.all()),
                        request.concurrency(),
                        request.mode()
                )
        ));
    }

    @PostMapping("/{id}/auth-identities")
    public ApiResponse<AdminBoundAuthIdentityResponse> bindUserAuthIdentity(
            @PathVariable long id,
            @RequestBody BindUserAuthIdentityRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(adminUserService.bindUserAuthIdentity(id, request));
    }

    @PostMapping("/{id}/replace-group")
    public ApiResponse<Map<String, Integer>> replaceUserGroup(
            @PathVariable long id,
            @Valid @RequestBody ReplaceUserGroupRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(Map.of(
                "migrated_keys",
                adminUserService.replaceUserGroup(id, request.old_group_id(), request.new_group_id())
        ));
    }
}
