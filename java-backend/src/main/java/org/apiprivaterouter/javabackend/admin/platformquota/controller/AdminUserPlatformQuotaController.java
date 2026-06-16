package org.apiprivaterouter.javabackend.admin.platformquota.controller;

import org.apiprivaterouter.javabackend.admin.platformquota.model.*;
import org.apiprivaterouter.javabackend.admin.platformquota.service.AdminUserPlatformQuotaService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserPlatformQuotaController {

    private final AdminUserPlatformQuotaService service;
    private final CurrentUserContext currentUserContext;

    public AdminUserPlatformQuotaController(AdminUserPlatformQuotaService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/{id}/platform-quotas")
    public ApiResponse<UserPlatformQuotaListResponse> listQuotas(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listQuotas(id));
    }

    @PutMapping("/{id}/platform-quotas")
    public ApiResponse<UserPlatformQuotaListResponse> replaceQuotas(
            @PathVariable long id,
            @RequestBody ReplaceUserPlatformQuotaRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.replaceQuotas(id, request));
    }

    @PostMapping("/{id}/platform-quotas/reset")
    public ApiResponse<ResetPlatformQuotaResponse> resetQuota(
            @PathVariable long id,
            @RequestBody ResetPlatformQuotaRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.resetQuota(id, request));
    }
}
