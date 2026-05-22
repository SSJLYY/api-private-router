package org.apiprivaterouter.javabackend.admin.ops.controller;

import org.apiprivaterouter.javabackend.admin.ops.service.AdminOpsService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/ops")
public class AdminOpsController {

    private final AdminOpsService service;
    private final CurrentUserContext currentUserContext;

    public AdminOpsController(AdminOpsService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/concurrency")
    public ApiResponse<Map<String, Object>> getConcurrencyStats(
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "group_id", required = false) Long groupId
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getConcurrencyStats(platform, groupId));
    }

    @GetMapping("/user-concurrency")
    public ApiResponse<Map<String, Object>> getUserConcurrencyStats() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getUserConcurrencyStats());
    }

    @GetMapping("/account-availability")
    public ApiResponse<Map<String, Object>> getAccountAvailability(
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "group_id", required = false) Long groupId
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getAccountAvailabilityStats(platform, groupId));
    }

    @GetMapping("/realtime-traffic")
    public ApiResponse<Map<String, Object>> getRealtimeTrafficSummary(
            @RequestParam(name = "window", required = false) String window,
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "group_id", required = false) Long groupId
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getRealtimeTrafficSummary(window, platform, groupId));
    }

    @GetMapping("/dashboard/overview")
    public ApiResponse<Map<String, Object>> getDashboardOverview(
            @RequestParam(name = "time_range", required = false) String timeRange,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(name = "mode", required = false) String mode
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getDashboardOverview(timeRange, startTime, endTime, platform, groupId));
    }

    @GetMapping("/dashboard/throughput-trend")
    public ApiResponse<Map<String, Object>> getDashboardThroughputTrend(
            @RequestParam(name = "time_range", required = false) String timeRange,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(name = "mode", required = false) String mode
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getDashboardThroughputTrend(timeRange, startTime, endTime, platform, groupId));
    }

    @GetMapping("/dashboard/latency-histogram")
    public ApiResponse<Map<String, Object>> getDashboardLatencyHistogram(
            @RequestParam(name = "time_range", required = false) String timeRange,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(name = "mode", required = false) String mode
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getLatencyHistogram(timeRange, startTime, endTime, platform, groupId));
    }

    @GetMapping("/dashboard/error-trend")
    public ApiResponse<Map<String, Object>> getDashboardErrorTrend(
            @RequestParam(name = "time_range", required = false) String timeRange,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(name = "mode", required = false) String mode
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getErrorTrend(timeRange, startTime, endTime, platform, groupId));
    }

    @GetMapping("/dashboard/error-distribution")
    public ApiResponse<Map<String, Object>> getDashboardErrorDistribution(
            @RequestParam(name = "time_range", required = false) String timeRange,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(name = "mode", required = false) String mode
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getErrorDistribution(timeRange, startTime, endTime, platform, groupId));
    }

    @GetMapping("/dashboard/snapshot-v2")
    public ApiResponse<Map<String, Object>> getDashboardSnapshotV2(
            @RequestParam(name = "time_range", required = false) String timeRange,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(name = "mode", required = false) String mode
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getDashboardSnapshotV2(timeRange, startTime, endTime, platform, groupId));
    }

    @GetMapping("/dashboard/openai-token-stats")
    public ApiResponse<Map<String, Object>> getOpenAiTokenStats(
            @RequestParam(name = "time_range", required = false) String timeRange,
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "group_id", required = false) Long groupId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "top_n", required = false) Integer topN
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getOpenAiTokenStats(timeRange, platform, groupId, page, pageSize, topN));
    }

    @GetMapping("/requests")
    public ApiResponse<PageResponse<Map<String, Object>>> listRequestDetails(
            @RequestParam Map<String, String> params,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listRequestDetails(new LinkedHashMap<>(params), page, pageSize));
    }

    @GetMapping("/errors")
    public ApiResponse<PageResponse<Map<String, Object>>> listErrors(
            @RequestParam Map<String, String> params,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listErrorLogs(new LinkedHashMap<>(params), page, pageSize, false));
    }

    @GetMapping("/errors/{id}")
    public ApiResponse<Map<String, Object>> getError(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getErrorDetail(id));
    }

    @GetMapping("/errors/{id}/retries")
    public ApiResponse<List<Map<String, Object>>> listErrorRetries(
            @PathVariable long id,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listRetryAttempts(id, limit));
    }

    @PostMapping("/errors/{id}/retry")
    public ApiResponse<Map<String, Object>> retryError(
            @PathVariable long id,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        String mode = request == null ? "client" : String.valueOf(request.getOrDefault("mode", "client"));
        Long pinnedAccountId = request == null ? null : castLong(request.get("pinned_account_id"));
        return ApiResponse.success(service.retryError(id, currentUser.userId(), mode, pinnedAccountId));
    }

    @PutMapping("/errors/{id}/resolve")
    public ApiResponse<Map<String, Object>> resolveError(
            @PathVariable long id,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        boolean resolved = request == null || !Boolean.FALSE.equals(request.get("resolved"));
        service.updateErrorResolved(id, resolved, currentUser.userId());
        return ApiResponse.success(Map.of("updated", true));
    }

    @GetMapping("/request-errors")
    public ApiResponse<PageResponse<Map<String, Object>>> listRequestErrors(
            @RequestParam Map<String, String> params,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listErrorLogs(new LinkedHashMap<>(params), page, pageSize, false));
    }

    @GetMapping("/request-errors/{id}")
    public ApiResponse<Map<String, Object>> getRequestError(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getErrorDetail(id));
    }

    @GetMapping("/request-errors/{id}/upstream-errors")
    public ApiResponse<PageResponse<Map<String, Object>>> listRequestErrorUpstreamErrors(
            @PathVariable long id,
            @RequestParam Map<String, String> params,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listRequestErrorUpstreamErrors(id, new LinkedHashMap<>(params), page, pageSize));
    }

    @PostMapping("/request-errors/{id}/retry-client")
    public ApiResponse<Map<String, Object>> retryRequestErrorClient(@PathVariable long id) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        return ApiResponse.success(service.retryClientError(id, currentUser.userId()));
    }

    @PostMapping("/request-errors/{id}/upstream-errors/{idx}/retry")
    public ApiResponse<Map<String, Object>> retryRequestErrorUpstream(
            @PathVariable long id,
            @PathVariable int idx
    ) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        return ApiResponse.success(service.retryUpstreamEvent(id, idx, currentUser.userId()));
    }

    @PutMapping("/request-errors/{id}/resolve")
    public ApiResponse<Map<String, Object>> resolveRequestError(
            @PathVariable long id,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        boolean resolved = request == null || !Boolean.FALSE.equals(request.get("resolved"));
        service.updateErrorResolved(id, resolved, currentUser.userId());
        return ApiResponse.success(Map.of("updated", true));
    }

    @GetMapping("/upstream-errors")
    public ApiResponse<PageResponse<Map<String, Object>>> listUpstreamErrors(
            @RequestParam Map<String, String> params,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listErrorLogs(new LinkedHashMap<>(params), page, pageSize, true));
    }

    @GetMapping("/upstream-errors/{id}")
    public ApiResponse<Map<String, Object>> getUpstreamError(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getErrorDetail(id));
    }

    @PostMapping("/upstream-errors/{id}/retry")
    public ApiResponse<Map<String, Object>> retryUpstreamError(@PathVariable long id) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        return ApiResponse.success(service.retryUpstreamError(id, currentUser.userId()));
    }

    @PutMapping("/upstream-errors/{id}/resolve")
    public ApiResponse<Map<String, Object>> resolveUpstreamError(
            @PathVariable long id,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        boolean resolved = request == null || !Boolean.FALSE.equals(request.get("resolved"));
        service.updateErrorResolved(id, resolved, currentUser.userId());
        return ApiResponse.success(Map.of("updated", true));
    }

    @GetMapping("/alert-rules")
    public ApiResponse<List<Map<String, Object>>> listAlertRules() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listAlertRules());
    }

    @PostMapping("/alert-rules")
    public ApiResponse<Map<String, Object>> createAlertRule(@RequestBody Map<String, Object> request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.createAlertRule(request));
    }

    @PutMapping("/alert-rules/{id}")
    public ApiResponse<Map<String, Object>> updateAlertRule(
            @PathVariable long id,
            @RequestBody Map<String, Object> request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateAlertRule(id, request));
    }

    @DeleteMapping("/alert-rules/{id}")
    public ApiResponse<Map<String, Object>> deleteAlertRule(@PathVariable long id) {
        currentUserContext.requireAdmin();
        service.deleteAlertRule(id);
        return ApiResponse.success(Map.of("deleted", true));
    }

    @GetMapping("/alert-events")
    public ApiResponse<List<Map<String, Object>>> listAlertEvents(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "email_sent", required = false) Boolean emailSent,
            @RequestParam(name = "time_range", required = false) String timeRange,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(name = "before_fired_at", required = false) String beforeFiredAt,
            @RequestParam(name = "before_id", required = false) Long beforeId,
            @RequestParam(name = "platform", required = false) String platform,
            @RequestParam(name = "group_id", required = false) Long groupId
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listAlertEvents(
                limit, status, severity, emailSent, timeRange, startTime, endTime, beforeFiredAt, beforeId, platform, groupId
        ));
    }

    @GetMapping("/alert-events/{id}")
    public ApiResponse<Map<String, Object>> getAlertEvent(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getAlertEvent(id));
    }

    @PutMapping("/alert-events/{id}/status")
    public ApiResponse<Map<String, Object>> updateAlertEventStatus(
            @PathVariable long id,
            @RequestBody Map<String, Object> request
    ) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        service.updateAlertEventStatus(id, String.valueOf(request.getOrDefault("status", "")), currentUser.userId());
        return ApiResponse.success(Map.of("updated", true));
    }

    @PostMapping("/alert-silences")
    public ApiResponse<Map<String, Object>> createAlertSilence(@RequestBody Map<String, Object> request) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        return ApiResponse.success(service.createAlertSilence(request, currentUser.userId()));
    }

    @GetMapping("/email-notification/config")
    public ApiResponse<Map<String, Object>> getEmailNotificationConfig() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getEmailNotificationConfig());
    }

    @PutMapping("/email-notification/config")
    public ApiResponse<Map<String, Object>> updateEmailNotificationConfig(@RequestBody Map<String, Object> request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateEmailNotificationConfig(request));
    }

    @GetMapping("/runtime/alert")
    public ApiResponse<Map<String, Object>> getAlertRuntimeSettings() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getAlertRuntimeSettings());
    }

    @PutMapping("/runtime/alert")
    public ApiResponse<Map<String, Object>> updateAlertRuntimeSettings(@RequestBody Map<String, Object> request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateAlertRuntimeSettings(request));
    }

    @GetMapping("/runtime/logging")
    public ApiResponse<Map<String, Object>> getRuntimeLogConfig() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getRuntimeLogConfig());
    }

    @PutMapping("/runtime/logging")
    public ApiResponse<Map<String, Object>> updateRuntimeLogConfig(@RequestBody Map<String, Object> request) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateRuntimeLogConfig(request, currentUser.userId()));
    }

    @PostMapping("/runtime/logging/reset")
    public ApiResponse<Map<String, Object>> resetRuntimeLogConfig() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.resetRuntimeLogConfig());
    }

    @GetMapping("/advanced-settings")
    public ApiResponse<Map<String, Object>> getAdvancedSettings() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getAdvancedSettings());
    }

    @PutMapping("/advanced-settings")
    public ApiResponse<Map<String, Object>> updateAdvancedSettings(@RequestBody Map<String, Object> request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateAdvancedSettings(request));
    }

    @GetMapping("/settings/metric-thresholds")
    public ApiResponse<Map<String, Object>> getMetricThresholds() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getMetricThresholds());
    }

    @PutMapping("/settings/metric-thresholds")
    public ApiResponse<Map<String, Object>> updateMetricThresholds(@RequestBody Map<String, Object> request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateMetricThresholds(request));
    }

    @GetMapping("/system-logs")
    public ApiResponse<PageResponse<Map<String, Object>>> listSystemLogs(
            @RequestParam Map<String, String> params,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listSystemLogs(new LinkedHashMap<>(params), page, pageSize));
    }

    @PostMapping("/system-logs/cleanup")
    public ApiResponse<Map<String, Object>> cleanupSystemLogs(@RequestBody(required = false) Map<String, Object> request) {
        CurrentUser currentUser = currentUserContext.requireAdmin();
        long deleted = service.cleanupSystemLogs(request == null ? Map.of() : request, currentUser.userId());
        return ApiResponse.success(Map.of("deleted", deleted));
    }

    @GetMapping("/system-logs/health")
    public ApiResponse<Map<String, Object>> getSystemLogSinkHealth() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getSystemLogSinkHealth());
    }

    private Long castLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String raw && !raw.isBlank()) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
