package org.apiprivaterouter.javabackend.admin.system.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.apiprivaterouter.javabackend.admin.system.model.SystemUpdateInfoResponse;
import org.apiprivaterouter.javabackend.admin.system.model.SystemVersionResponse;
import org.apiprivaterouter.javabackend.admin.system.service.AdminSystemService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/system")
public class AdminSystemController {

    private final AdminSystemService service;
    private final CurrentUserContext currentUserContext;

    public AdminSystemController(AdminSystemService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/version")
    public ApiResponse<SystemVersionResponse> getVersion() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getVersion());
    }

    @GetMapping("/check-updates")
    public ApiResponse<SystemUpdateInfoResponse> checkUpdates(
            @RequestParam(name = "force", required = false, defaultValue = "false") boolean force
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.checkUpdates(force));
    }

    @PostMapping("/update")
    public ApiResponse<Map<String, Object>> performUpdate(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        CurrentUser admin = currentUserContext.requireAdmin();
        return ApiResponse.success(service.performUpdate(idempotencyKey, admin.userId(), request.getRequestURI()));
    }

    @PostMapping("/rollback")
    public ApiResponse<Map<String, Object>> rollback(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        CurrentUser admin = currentUserContext.requireAdmin();
        return ApiResponse.success(service.rollback(idempotencyKey, admin.userId(), request.getRequestURI()));
    }

    @PostMapping("/restart")
    public ApiResponse<Map<String, Object>> restart(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        CurrentUser admin = currentUserContext.requireAdmin();
        return ApiResponse.success(service.restart(idempotencyKey, admin.userId(), request.getRequestURI()));
    }
}
