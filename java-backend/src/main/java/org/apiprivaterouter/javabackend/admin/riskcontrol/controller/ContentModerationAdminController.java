package org.apiprivaterouter.javabackend.admin.riskcontrol.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ClearFlaggedHashesResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationConfigResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationLogResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationRuntimeStatusResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationUnbanUserResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.DeleteFlaggedHashRequest;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.DeleteFlaggedHashResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.TestContentModerationApiKeysRequest;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.TestContentModerationApiKeysResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.UpdateContentModerationConfigRequest;
import org.apiprivaterouter.javabackend.admin.riskcontrol.service.ContentModerationAdminService;
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

@RestController
@RequestMapping("/api/v1/admin/risk-control")
public class ContentModerationAdminController {

    private final ContentModerationAdminService service;
    private final CurrentUserContext currentUserContext;

    public ContentModerationAdminController(ContentModerationAdminService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/config")
    public ApiResponse<ContentModerationConfigResponse> getConfig() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getConfig());
    }

    @PutMapping("/config")
    public ApiResponse<ContentModerationConfigResponse> updateConfig(
            @RequestBody UpdateContentModerationConfigRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateConfig(request));
    }

    @GetMapping("/status")
    public ApiResponse<ContentModerationRuntimeStatusResponse> getStatus() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getStatus());
    }

    @PostMapping("/api-keys/test")
    public ApiResponse<TestContentModerationApiKeysResponse> testApiKeys(
            @RequestBody(required = false) TestContentModerationApiKeysRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.testApiKeys(request));
    }

    @GetMapping("/logs")
    public ApiResponse<PageResponse<ContentModerationLogResponse>> listLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String result,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listLogs(page, pageSize, result, groupId, endpoint, search, from, to));
    }

    @PostMapping("/users/{userId}/unban")
    public ApiResponse<ContentModerationUnbanUserResponse> unbanUser(@PathVariable long userId) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.unbanUser(userId));
    }

    @DeleteMapping("/hashes")
    public ApiResponse<DeleteFlaggedHashResponse> deleteFlaggedHash(
            @Valid @RequestBody DeleteFlaggedHashRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.deleteFlaggedHash(request));
    }

    @DeleteMapping("/hashes/all")
    public ApiResponse<ClearFlaggedHashesResponse> clearFlaggedHashes() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.clearFlaggedHashes());
    }
}
