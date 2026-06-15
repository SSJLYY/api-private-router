package org.apiprivaterouter.javabackend.admin.datamanagement.controller;

import org.apiprivaterouter.javabackend.admin.datamanagement.model.DataManagementAgentHealthResponse;
import org.apiprivaterouter.javabackend.admin.datamanagement.service.AdminDataManagementService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/data-management")
public class AdminDataManagementController {

    private final AdminDataManagementService service;
    private final CurrentUserContext currentUserContext;

    public AdminDataManagementController(AdminDataManagementService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/agent/health")
    public ApiResponse<DataManagementAgentHealthResponse> getAgentHealth() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getAgentHealth());
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig() {
        throwDeprecated();
        throw service.deprecatedError(); // unreachable, satisfies compiler
    }

    @PutMapping("/config")
    public ApiResponse<Map<String, Object>> updateConfig(@RequestBody(required = false) Map<String, Object> ignored) {
        throwDeprecated();
        throw service.deprecatedError();
    }

    @GetMapping("/sources/{sourceType}/profiles")
    public ApiResponse<Map<String, Object>> listSourceProfiles(@PathVariable String sourceType) {
        validateSourceType(sourceType);
        throwDeprecated();
        throw service.deprecatedError();
    }

    @PostMapping("/sources/{sourceType}/profiles")
    public ApiResponse<Map<String, Object>> createSourceProfile(@PathVariable String sourceType, @RequestBody(required = false) Map<String, Object> ignored) {
        validateSourceType(sourceType);
        throwDeprecated();
        throw service.deprecatedError();
    }

    @PutMapping("/sources/{sourceType}/profiles/{profileId}")
    public ApiResponse<Map<String, Object>> updateSourceProfile(
            @PathVariable String sourceType,
            @PathVariable String profileId,
            @RequestBody(required = false) Map<String, Object> ignored
    ) {
        validateSourceType(sourceType);
        validateNonBlank(profileId, "Invalid profile_id");
        throwDeprecated();
        throw service.deprecatedError();
    }

    @DeleteMapping("/sources/{sourceType}/profiles/{profileId}")
    public ApiResponse<Map<String, Object>> deleteSourceProfile(@PathVariable String sourceType, @PathVariable String profileId) {
        validateSourceType(sourceType);
        validateNonBlank(profileId, "Invalid profile_id");
        throwDeprecated();
        throw service.deprecatedError();
    }

    @PostMapping("/sources/{sourceType}/profiles/{profileId}/activate")
    public ApiResponse<Map<String, Object>> activateSourceProfile(@PathVariable String sourceType, @PathVariable String profileId) {
        validateSourceType(sourceType);
        validateNonBlank(profileId, "Invalid profile_id");
        throwDeprecated();
        throw service.deprecatedError();
    }

    @PostMapping("/s3/test")
    public ApiResponse<Map<String, Object>> testS3(@RequestBody(required = false) Map<String, Object> ignored) {
        throwDeprecated();
        throw service.deprecatedError();
    }

    @GetMapping("/s3/profiles")
    public ApiResponse<Map<String, Object>> listS3Profiles() {
        throwDeprecated();
        throw service.deprecatedError();
    }

    @PostMapping("/s3/profiles")
    public ApiResponse<Map<String, Object>> createS3Profile(@RequestBody(required = false) Map<String, Object> ignored) {
        throwDeprecated();
        throw service.deprecatedError();
    }

    @PutMapping("/s3/profiles/{profileId}")
    public ApiResponse<Map<String, Object>> updateS3Profile(@PathVariable String profileId, @RequestBody(required = false) Map<String, Object> ignored) {
        validateNonBlank(profileId, "Invalid profile_id");
        throwDeprecated();
        throw service.deprecatedError();
    }

    @DeleteMapping("/s3/profiles/{profileId}")
    public ApiResponse<Map<String, Object>> deleteS3Profile(@PathVariable String profileId) {
        validateNonBlank(profileId, "Invalid profile_id");
        throwDeprecated();
        throw service.deprecatedError();
    }

    @PostMapping("/s3/profiles/{profileId}/activate")
    public ApiResponse<Map<String, Object>> activateS3Profile(@PathVariable String profileId) {
        validateNonBlank(profileId, "Invalid profile_id");
        throwDeprecated();
        throw service.deprecatedError();
    }

    @PostMapping("/backups")
    public ApiResponse<Map<String, Object>> createBackupJob(@RequestBody(required = false) Map<String, Object> ignored) {
        throwDeprecated();
        throw service.deprecatedError();
    }

    @GetMapping("/backups")
    public ApiResponse<Map<String, Object>> listBackupJobs() {
        throwDeprecated();
        throw service.deprecatedError();
    }

    @GetMapping("/backups/{jobId}")
    public ApiResponse<Map<String, Object>> getBackupJob(@PathVariable String jobId) {
        validateNonBlank(jobId, "Invalid backup job ID");
        throwDeprecated();
        throw service.deprecatedError();
    }

    private void throwDeprecated() {
        currentUserContext.requireAdmin();
        throw service.deprecatedError();
    }

    private void validateSourceType(String sourceType) {
        String normalized = sourceType == null ? "" : sourceType.trim();
        if (!"postgres".equals(normalized) && !"redis".equals(normalized)) {
            throw new IllegalArgumentException("source_type must be postgres or redis");
        }
    }

    private void validateNonBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }
}
