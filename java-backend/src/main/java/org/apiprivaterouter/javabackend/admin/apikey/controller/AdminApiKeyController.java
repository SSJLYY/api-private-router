package org.apiprivaterouter.javabackend.admin.apikey.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.apikey.model.AdminUpdateApiKeyGroupRequest;
import org.apiprivaterouter.javabackend.admin.apikey.model.AdminUpdateApiKeyGroupResponse;
import org.apiprivaterouter.javabackend.admin.apikey.service.AdminApiKeyService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/api-keys")
public class AdminApiKeyController {

    private final AdminApiKeyService service;
    private final CurrentUserContext currentUserContext;

    public AdminApiKeyController(AdminApiKeyService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminUpdateApiKeyGroupResponse> update(@PathVariable long id, @Valid @RequestBody AdminUpdateApiKeyGroupRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateApiKey(id, request));
    }
}
