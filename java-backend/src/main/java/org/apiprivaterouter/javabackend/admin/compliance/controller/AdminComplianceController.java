package org.apiprivaterouter.javabackend.admin.compliance.controller;

import org.apiprivaterouter.javabackend.admin.compliance.model.*;
import org.apiprivaterouter.javabackend.admin.compliance.service.AdminComplianceService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/compliance")
public class AdminComplianceController {

    private final AdminComplianceService service;
    private final CurrentUserContext currentUserContext;

    public AdminComplianceController(AdminComplianceService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<AdminComplianceStatusResponse> getStatus() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getStatus());
    }

    @PostMapping("/accept")
    public ApiResponse<AdminComplianceStatusResponse> accept(
            @RequestBody AdminComplianceAcceptRequest request,
            HttpServletRequest httpRequest
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.accept(request, httpRequest));
    }
}
