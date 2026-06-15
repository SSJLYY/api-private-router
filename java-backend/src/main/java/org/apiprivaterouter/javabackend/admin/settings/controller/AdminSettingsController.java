package org.apiprivaterouter.javabackend.admin.settings.controller;

import jakarta.validation.Valid;

import org.apiprivaterouter.javabackend.admin.settings.model.AdminApiKeyStatusResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.BetaPolicySettingsResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.OverloadCooldownSettingsResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.RateLimit429CooldownSettingsResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.RectifierSettingsResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.SendTestEmailRequest;
import org.apiprivaterouter.javabackend.admin.settings.model.StreamTimeoutSettingsResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.TestSmtpRequest;
import org.apiprivaterouter.javabackend.admin.settings.model.WebSearchEmulationConfigResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.WebSearchEmulationTestRequest;
import org.apiprivaterouter.javabackend.admin.settings.model.WebSearchTestResult;
import org.apiprivaterouter.javabackend.admin.settings.model.WebSearchUsageResetRequest;
import org.apiprivaterouter.javabackend.admin.settings.service.AdminSettingsService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/settings")
public class AdminSettingsController {

    private final AdminSettingsService service;
    private final CurrentUserContext currentUserContext;

    public AdminSettingsController(AdminSettingsService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> getSettingsOverview() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getSettingsOverview());
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> updateSettingsOverview(@RequestBody Map<String, Object> request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateSettingsOverview(request));
    }

    @PostMapping("/test-smtp")
    public ApiResponse<Map<String, String>> testSmtp(@Valid @RequestBody TestSmtpRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.testSmtpConnection(request));
    }

    @PostMapping("/send-test-email")
    public ApiResponse<Map<String, String>> sendTestEmail(@Valid @RequestBody SendTestEmailRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.sendTestEmail(request));
    }

    @GetMapping("/admin-api-key")
    public ApiResponse<AdminApiKeyStatusResponse> getAdminApiKeyStatus() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getAdminApiKeyStatus());
    }

    @PostMapping("/admin-api-key/regenerate")
    public ApiResponse<Map<String, String>> regenerateAdminApiKey() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(Map.of("key", service.regenerateAdminApiKey()));
    }

    @DeleteMapping("/admin-api-key")
    public ApiResponse<Map<String, String>> deleteAdminApiKey() {
        currentUserContext.requireAdmin();
        service.deleteAdminApiKey();
        return ApiResponse.success(Map.of("message", "Admin API key deleted"));
    }

    @GetMapping("/overload-cooldown")
    public ApiResponse<OverloadCooldownSettingsResponse> getOverloadCooldownSettings() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getOverloadCooldownSettings());
    }

    @PutMapping("/overload-cooldown")
    public ApiResponse<OverloadCooldownSettingsResponse> updateOverloadCooldownSettings(
            @RequestBody OverloadCooldownSettingsResponse request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateOverloadCooldownSettings(request));
    }

    @GetMapping("/rate-limit-429-cooldown")
    public ApiResponse<RateLimit429CooldownSettingsResponse> getRateLimit429CooldownSettings() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getRateLimit429CooldownSettings());
    }

    @PutMapping("/rate-limit-429-cooldown")
    public ApiResponse<RateLimit429CooldownSettingsResponse> updateRateLimit429CooldownSettings(
            @RequestBody RateLimit429CooldownSettingsResponse request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateRateLimit429CooldownSettings(request));
    }

    @GetMapping("/stream-timeout")
    public ApiResponse<StreamTimeoutSettingsResponse> getStreamTimeoutSettings() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getStreamTimeoutSettings());
    }

    @PutMapping("/stream-timeout")
    public ApiResponse<StreamTimeoutSettingsResponse> updateStreamTimeoutSettings(
            @RequestBody StreamTimeoutSettingsResponse request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateStreamTimeoutSettings(request));
    }

    @GetMapping("/rectifier")
    public ApiResponse<RectifierSettingsResponse> getRectifierSettings() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getRectifierSettings());
    }

    @PutMapping("/rectifier")
    public ApiResponse<RectifierSettingsResponse> updateRectifierSettings(
            @RequestBody RectifierSettingsResponse request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateRectifierSettings(request));
    }

    @GetMapping("/beta-policy")
    public ApiResponse<BetaPolicySettingsResponse> getBetaPolicySettings() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getBetaPolicySettings());
    }

    @PutMapping("/beta-policy")
    public ApiResponse<BetaPolicySettingsResponse> updateBetaPolicySettings(
            @RequestBody BetaPolicySettingsResponse request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateBetaPolicySettings(request));
    }

    @GetMapping("/web-search-emulation")
    public ApiResponse<WebSearchEmulationConfigResponse> getWebSearchEmulationConfig() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getWebSearchEmulationConfig());
    }

    @PutMapping("/web-search-emulation")
    public ApiResponse<WebSearchEmulationConfigResponse> updateWebSearchEmulationConfig(
            @RequestBody WebSearchEmulationConfigResponse request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateWebSearchEmulationConfig(request));
    }

    @PostMapping("/web-search-emulation/reset-usage")
    public ApiResponse<Map<String, String>> resetWebSearchUsage(@RequestBody WebSearchUsageResetRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.resetWebSearchUsage(request));
    }

    @PostMapping("/web-search-emulation/test")
    public ApiResponse<WebSearchTestResult> testWebSearchEmulation(@RequestBody WebSearchEmulationTestRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.testWebSearchEmulation(request));
    }
}
