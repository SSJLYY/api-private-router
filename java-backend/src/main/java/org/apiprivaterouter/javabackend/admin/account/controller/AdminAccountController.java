package org.apiprivaterouter.javabackend.admin.account.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountDataImportRequest;
import org.apiprivaterouter.javabackend.admin.account.model.AccountRefreshWarningResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AdminDataPayload;
import org.apiprivaterouter.javabackend.admin.account.model.AdminDataImportResult;
import org.apiprivaterouter.javabackend.admin.account.model.AccountAvailableModelResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountUsageInfoResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountUsageStatsResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountTestRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BatchCreateAccountsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BatchCreateAccountsResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BatchClearErrorRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BatchRefreshRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BatchRefreshResultResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BatchOperationResultResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BatchRefreshTierRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BatchTodayStatsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BatchTodayStatsResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BatchUpdateCredentialsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.CheckMixedChannelRequest;
import org.apiprivaterouter.javabackend.admin.account.model.CheckMixedChannelResponse;
import org.apiprivaterouter.javabackend.admin.account.model.ClaudeOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.account.model.CookieAuthRequest;
import org.apiprivaterouter.javabackend.admin.account.model.CrsPreviewAccount;
import org.apiprivaterouter.javabackend.admin.account.model.CreateAccountRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BulkUpdateAccountsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BulkUpdateAccountsResponse;
import org.apiprivaterouter.javabackend.admin.account.model.ExchangeAuthCodeRequest;
import org.apiprivaterouter.javabackend.admin.account.model.GenerateAuthUrlRequest;
import org.apiprivaterouter.javabackend.admin.account.model.GenerateAuthUrlResponse;
import org.apiprivaterouter.javabackend.admin.account.model.PreviewFromCrsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.PreviewFromCrsResult;
import org.apiprivaterouter.javabackend.admin.account.model.RefreshTierResponse;
import org.apiprivaterouter.javabackend.admin.account.model.SetSchedulableRequest;
import org.apiprivaterouter.javabackend.admin.account.model.SyncFromCrsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.SyncFromCrsResult;
import org.apiprivaterouter.javabackend.admin.account.model.TempUnschedulableStatusResponse;
import org.apiprivaterouter.javabackend.admin.account.model.UpdateAccountRequest;
import org.apiprivaterouter.javabackend.admin.account.model.WindowStatsResponse;
import org.apiprivaterouter.javabackend.admin.account.service.AdminAccountService;
import org.apiprivaterouter.javabackend.admin.account.service.AdminAccountTestService;
import org.apiprivaterouter.javabackend.admin.account.service.ClaudeOAuthService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/admin/accounts")
public class AdminAccountController {

    private final AdminAccountService service;
    private final AdminAccountTestService accountTestService;
    private final ClaudeOAuthService claudeOAuthService;
    private final CurrentUserContext currentUserContext;

    public AdminAccountController(
            AdminAccountService service,
            AdminAccountTestService accountTestService,
            ClaudeOAuthService claudeOAuthService,
            CurrentUserContext currentUserContext
    ) {
        this.service = service;
        this.accountTestService = accountTestService;
        this.claudeOAuthService = claudeOAuthService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminAccountResponse>> listAccounts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String group,
            @RequestParam(name = "privacy_mode", required = false) String privacyMode,
            @RequestParam(required = false) String search,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder,
            @RequestParam(required = false) String lite,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch,
            HttpServletResponse response
    ) {
        currentUserContext.requireAdmin();
        boolean liteMode = "1".equals(lite) || "true".equalsIgnoreCase(lite);
        PageResponse<AdminAccountResponse> result = service.listAccounts(
                page, pageSize, platform, type, status, group, privacyMode, search, sortBy, sortOrder, liteMode
        );
        String etag = service.computeListEtag(result, platform, type, status, group, privacyMode, search, sortBy, sortOrder, liteMode);
        if (etag != null && !etag.isBlank()) {
            response.setHeader("ETag", etag);
            response.setHeader("Vary", "If-None-Match");
            if (etag.equals(ifNoneMatch)) {
                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
                return null;
            }
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminAccountResponse> getAccount(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getAccount(id));
    }

    @GetMapping("/{id}/models")
    public ApiResponse<List<AccountAvailableModelResponse>> getAvailableModels(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getAvailableModels(id));
    }

    @GetMapping("/{id}/stats")
    public ApiResponse<AccountUsageStatsResponse> getAccountUsageStats(
            @PathVariable long id,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getAccountUsageStats(id, days, timezone));
    }

    @GetMapping("/{id}/usage")
    public ApiResponse<AccountUsageInfoResponse> getUsageInfo(
            @PathVariable long id,
            @RequestParam(required = false) String source
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getUsageInfo(id, source));
    }

    @GetMapping("/data")
    public ApiResponse<AdminDataPayload> exportData(
            @RequestParam(required = false) List<Long> ids,
            @RequestParam(name = "include_proxies", defaultValue = "true") boolean includeProxies,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String group,
            @RequestParam(name = "privacy_mode", required = false) String privacyMode,
            @RequestParam(required = false) String search,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.exportData(
                ids,
                includeProxies,
                platform,
                type,
                status,
                group,
                privacyMode,
                search,
                sortBy,
                sortOrder
        ));
    }

    @GetMapping("/antigravity/default-model-mapping")
    public ApiResponse<Map<String, String>> getAntigravityDefaultModelMapping() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getAntigravityDefaultModelMapping());
    }

    @PostMapping("/data")
    public ApiResponse<AdminDataImportResult> importData(@Valid @RequestBody AccountDataImportRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.importData(request));
    }

    @PostMapping
    public ApiResponse<AdminAccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.createAccount(request));
    }

    @PostMapping("/batch")
    public ApiResponse<BatchCreateAccountsResponse> batchCreate(@Valid @RequestBody BatchCreateAccountsRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.batchCreate(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminAccountResponse> updateAccount(@PathVariable long id, @RequestBody UpdateAccountRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateAccount(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> deleteAccount(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.deleteAccount(id));
    }

    @PostMapping("/check-mixed-channel")
    public ApiResponse<CheckMixedChannelResponse> checkMixedChannel(@Valid @RequestBody CheckMixedChannelRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.checkMixedChannelRisk(request));
    }

    @PostMapping("/today-stats/batch")
    public ApiResponse<BatchTodayStatsResponse> getBatchTodayStats(
            @RequestBody BatchTodayStatsRequest request,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getBatchTodayStats(request, timezone));
    }

    @PostMapping("/batch-clear-error")
    public ApiResponse<BatchOperationResultResponse> batchClearError(@RequestBody BatchClearErrorRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.batchClearError(request));
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<?> refreshAccount(@PathVariable long id) {
        currentUserContext.requireAdmin();
        Object result = service.refreshAccount(id);
        if (result instanceof AdminAccountResponse account) {
            return ResponseEntity.ok(ApiResponse.success(account));
        }
        return ResponseEntity.ok(ApiResponse.success((AccountRefreshWarningResponse) result));
    }

    @PostMapping("/batch-refresh")
    public ApiResponse<BatchRefreshResultResponse> batchRefresh(@RequestBody BatchRefreshRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.batchRefresh(request));
    }

    @PostMapping(value = "/{id}/test", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody testAccount(
            @PathVariable long id,
            @RequestBody(required = false) AccountTestRequest request,
            HttpServletResponse response
    ) {
        currentUserContext.requireAdmin();
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");
        return accountTestService.buildAccountTestStream(id, request);
    }

    @PostMapping({"/sync/remote-source/preview", "/sync/crs/preview"})
    public ApiResponse<PreviewFromCrsResult> previewFromCrs(@Valid @RequestBody PreviewFromCrsRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.previewFromCrs(request));
    }

    @PostMapping({"/sync/remote-source", "/sync/crs"})
    public ApiResponse<SyncFromCrsResult> syncFromCrs(@Valid @RequestBody SyncFromCrsRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.syncFromCrs(request));
    }

    @PostMapping("/generate-auth-url")
    public ApiResponse<GenerateAuthUrlResponse> generateAuthUrl(@RequestBody(required = false) GenerateAuthUrlRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(claudeOAuthService.generateAuthUrl(request == null ? null : request.proxy_id()));
    }

    @PostMapping("/generate-setup-token-url")
    public ApiResponse<GenerateAuthUrlResponse> generateSetupTokenUrl(@RequestBody(required = false) GenerateAuthUrlRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(claudeOAuthService.generateSetupTokenUrl(request == null ? null : request.proxy_id()));
    }

    @PostMapping("/exchange-code")
    public ApiResponse<ClaudeOAuthTokenResponse> exchangeCode(@Valid @RequestBody ExchangeAuthCodeRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(claudeOAuthService.exchangeCode(request));
    }

    @PostMapping("/exchange-setup-token-code")
    public ApiResponse<ClaudeOAuthTokenResponse> exchangeSetupTokenCode(@Valid @RequestBody ExchangeAuthCodeRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(claudeOAuthService.exchangeCode(request));
    }

    @PostMapping("/cookie-auth")
    public ApiResponse<ClaudeOAuthTokenResponse> cookieAuth(@Valid @RequestBody CookieAuthRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(claudeOAuthService.cookieAuth(request, false));
    }

    @PostMapping("/setup-token-cookie-auth")
    public ApiResponse<ClaudeOAuthTokenResponse> setupTokenCookieAuth(@Valid @RequestBody CookieAuthRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(claudeOAuthService.cookieAuth(request, true));
    }

    @PostMapping("/{id}/set-privacy")
    public ApiResponse<AdminAccountResponse> setPrivacy(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.setPrivacy(id));
    }

    @PostMapping("/{id}/refresh-tier")
    public ApiResponse<RefreshTierResponse> refreshTier(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.refreshTier(id));
    }

    @PostMapping("/batch-refresh-tier")
    public ApiResponse<BatchOperationResultResponse> batchRefreshTier(@RequestBody(required = false) BatchRefreshTierRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.batchRefreshTier(request));
    }

    @PostMapping("/batch-update-credentials")
    public ApiResponse<BulkUpdateAccountsResponse> batchUpdateCredentials(@RequestBody BatchUpdateCredentialsRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.batchUpdateCredentials(request));
    }

    @PostMapping("/bulk-update")
    public ApiResponse<BulkUpdateAccountsResponse> bulkUpdate(@RequestBody BulkUpdateAccountsRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.bulkUpdate(request));
    }

    @PostMapping("/{id}/schedulable")
    public ApiResponse<AdminAccountResponse> setSchedulable(@PathVariable long id, @RequestBody SetSchedulableRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.setSchedulable(id, request));
    }

    @GetMapping("/{id}/temp-unschedulable")
    public ApiResponse<TempUnschedulableStatusResponse> getTempUnschedulableStatus(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getTempUnschedulableStatus(id));
    }

    @DeleteMapping("/{id}/temp-unschedulable")
    public ApiResponse<Map<String, String>> clearTempUnschedulable(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.clearTempUnschedulable(id));
    }

    @PostMapping("/{id}/clear-error")
    public ApiResponse<AdminAccountResponse> clearError(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.clearError(id));
    }

    @PostMapping("/{id}/clear-rate-limit")
    public ApiResponse<AdminAccountResponse> clearRateLimit(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.clearRateLimit(id));
    }

    @PostMapping("/{id}/recover-state")
    public ApiResponse<AdminAccountResponse> recoverState(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.recoverState(id));
    }

    @PostMapping("/{id}/reset-quota")
    public ApiResponse<AdminAccountResponse> resetQuota(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.resetQuota(id));
    }

    @GetMapping("/{id}/today-stats")
    public ApiResponse<WindowStatsResponse> getTodayStats(
            @PathVariable long id,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getTodayStats(id, timezone));
    }
}
