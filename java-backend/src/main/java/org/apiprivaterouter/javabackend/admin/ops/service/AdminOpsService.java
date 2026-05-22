package org.apiprivaterouter.javabackend.admin.ops.service;

import org.apiprivaterouter.javabackend.admin.ops.repository.AdminOpsRepository;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminOpsService {

    private static final String RETRY_MODE_CLIENT = "client";
    private static final String RETRY_MODE_UPSTREAM = "upstream";
    private static final String RETRY_MODE_UPSTREAM_EVENT = "upstream_event";
    private static final String RETRY_STATUS_RUNNING = "running";
    private static final String RETRY_STATUS_SUCCEEDED = "succeeded";
    private static final String RETRY_STATUS_FAILED = "failed";
    private static final Duration RETRY_MIN_INTERVAL = Duration.ofSeconds(10);

    private static final List<String> VALID_ALERT_METRICS = List.of(
            "success_rate",
            "error_rate",
            "upstream_error_rate",
            "cpu_usage_percent",
            "memory_usage_percent",
            "concurrency_queue_depth",
            "group_available_accounts",
            "group_available_ratio",
            "group_rate_limit_ratio",
            "account_rate_limited_count",
            "account_error_count",
            "account_error_ratio",
            "overload_account_count"
    );
    private static final List<String> VALID_ALERT_OPERATORS = List.of(">", "<", ">=", "<=", "==", "!=");
    private static final List<String> VALID_ALERT_SEVERITIES = List.of("P0", "P1", "P2", "P3", "critical", "warning", "info");

    private final AdminOpsRepository repository;
    private final AdminOpsRetryExecutor retryExecutor;

    public AdminOpsService(AdminOpsRepository repository, AdminOpsRetryExecutor retryExecutor) {
        this.repository = repository;
        this.retryExecutor = retryExecutor;
    }

    public Map<String, Object> getConcurrencyStats(String platform, Long groupId) {
        requireMonitoringEnabled();
        Map<String, Object> stats = repository.getConcurrencyStats(blankToNull(platform), positiveGroupId(groupId));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", true);
        payload.put("platform", stats.getOrDefault("platform", List.of()));
        payload.put("group", stats.getOrDefault("group", List.of()));
        payload.put("account", stats.getOrDefault("account", List.of()));
        payload.put("timestamp", now());
        return payload;
    }

    public Map<String, Object> getUserConcurrencyStats() {
        requireMonitoringEnabled();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", true);
        payload.put("user", repository.getUserConcurrencyStats());
        payload.put("timestamp", now());
        return payload;
    }

    public Map<String, Object> getAccountAvailabilityStats(String platform, Long groupId) {
        requireMonitoringEnabled();
        Map<String, Object> stats = repository.getAccountAvailabilityStats(blankToNull(platform), positiveGroupId(groupId));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", true);
        payload.put("platform", stats.getOrDefault("platform", List.of()));
        payload.put("group", stats.getOrDefault("group", List.of()));
        payload.put("account", stats.getOrDefault("account", List.of()));
        payload.put("timestamp", now());
        return payload;
    }

    public Map<String, Object> getRealtimeTrafficSummary(String window, String platform, Long groupId) {
        TimeWindow timeWindow = resolveRealtimeWindow(window);
        Map<String, Object> summary = repository.getRealtimeTrafficSummary(
                timeWindow.startInstant(),
                timeWindow.endInstant(),
                blankToNull(platform),
                positiveGroupId(groupId)
        );
        summary.put("window", timeWindow.label());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", true);
        payload.put("summary", summary);
        payload.put("timestamp", now());
        return payload;
    }

    public Map<String, Object> getDashboardOverview(
            String timeRange,
            String startTime,
            String endTime,
            String platform,
            Long groupId
    ) {
        TimeWindow timeWindow = resolveDashboardWindow(timeRange, startTime, endTime, Duration.ofHours(1));
        return repository.getDashboardOverview(
                timeWindow.startInstant(),
                timeWindow.endInstant(),
                blankToNull(platform),
                positiveGroupId(groupId)
        );
    }

    public Map<String, Object> getDashboardThroughputTrend(
            String timeRange,
            String startTime,
            String endTime,
            String platform,
            Long groupId
    ) {
        TimeWindow timeWindow = resolveDashboardWindow(timeRange, startTime, endTime, Duration.ofHours(1));
        return repository.getThroughputTrend(
                timeWindow.startInstant(),
                timeWindow.endInstant(),
                blankToNull(platform),
                positiveGroupId(groupId),
                bucketSeconds(timeWindow.duration())
        );
    }

    public Map<String, Object> getLatencyHistogram(
            String timeRange,
            String startTime,
            String endTime,
            String platform,
            Long groupId
    ) {
        TimeWindow timeWindow = resolveDashboardWindow(timeRange, startTime, endTime, Duration.ofHours(1));
        return repository.getLatencyHistogram(
                timeWindow.startInstant(),
                timeWindow.endInstant(),
                blankToNull(platform),
                positiveGroupId(groupId)
        );
    }

    public Map<String, Object> getErrorTrend(
            String timeRange,
            String startTime,
            String endTime,
            String platform,
            Long groupId
    ) {
        TimeWindow timeWindow = resolveDashboardWindow(timeRange, startTime, endTime, Duration.ofHours(1));
        return repository.getErrorTrend(
                timeWindow.startInstant(),
                timeWindow.endInstant(),
                blankToNull(platform),
                positiveGroupId(groupId),
                bucketSeconds(timeWindow.duration())
        );
    }

    public Map<String, Object> getErrorDistribution(
            String timeRange,
            String startTime,
            String endTime,
            String platform,
            Long groupId
    ) {
        TimeWindow timeWindow = resolveDashboardWindow(timeRange, startTime, endTime, Duration.ofHours(1));
        return repository.getErrorDistribution(
                timeWindow.startInstant(),
                timeWindow.endInstant(),
                blankToNull(platform),
                positiveGroupId(groupId)
        );
    }

    public Map<String, Object> getDashboardSnapshotV2(
            String timeRange,
            String startTime,
            String endTime,
            String platform,
            Long groupId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generated_at", now());
        payload.put("overview", getDashboardOverview(timeRange, startTime, endTime, platform, groupId));
        payload.put("throughput_trend", getDashboardThroughputTrend(timeRange, startTime, endTime, platform, groupId));
        payload.put("error_trend", getErrorTrend(timeRange, startTime, endTime, platform, groupId));
        return payload;
    }

    public Map<String, Object> getOpenAiTokenStats(
            String timeRange,
            String platform,
            Long groupId,
            Integer page,
            Integer pageSize,
            Integer topN
    ) {
        TimeWindow timeWindow = resolveDashboardWindow(timeRange, null, null, Duration.ofDays(30));
        return repository.getOpenAiTokenStats(
                timeWindow.startInstant(),
                timeWindow.endInstant(),
                blankToDefault(timeRange, "30d"),
                blankToNull(platform),
                positiveGroupId(groupId),
                safePage(page),
                safePageSize(pageSize),
                topN
        );
    }

    public Map<String, Object> getEmailNotificationConfig() {
        Map<String, Object> stored = repository.loadJsonSetting(AdminOpsRepository.KEY_EMAIL_NOTIFICATION_CONFIG);
        if (!stored.isEmpty()) {
            return mergeEmailNotificationConfig(stored);
        }
        return defaultEmailNotificationConfig();
    }

    public Map<String, Object> updateEmailNotificationConfig(Map<String, Object> request) {
        Map<String, Object> merged = mergeEmailNotificationConfig(request);
        repository.saveJsonSetting(AdminOpsRepository.KEY_EMAIL_NOTIFICATION_CONFIG, merged);
        return merged;
    }

    public Map<String, Object> getAlertRuntimeSettings() {
        Map<String, Object> stored = repository.loadJsonSetting(AdminOpsRepository.KEY_RUNTIME_ALERT_SETTINGS);
        if (!stored.isEmpty()) {
            return mergeAlertRuntimeSettings(stored);
        }
        return defaultAlertRuntimeSettings();
    }

    public Map<String, Object> updateAlertRuntimeSettings(Map<String, Object> request) {
        Map<String, Object> merged = mergeAlertRuntimeSettings(request);
        repository.saveJsonSetting(AdminOpsRepository.KEY_RUNTIME_ALERT_SETTINGS, merged);
        return merged;
    }

    public Map<String, Object> getRuntimeLogConfig() {
        Map<String, Object> stored = repository.loadJsonSetting(AdminOpsRepository.KEY_RUNTIME_LOG_CONFIG);
        if (!stored.isEmpty()) {
            return mergeRuntimeLogConfig(stored);
        }
        return defaultRuntimeLogConfig(null);
    }

    public Map<String, Object> updateRuntimeLogConfig(Map<String, Object> request, Long userId) {
        Map<String, Object> merged = mergeRuntimeLogConfig(request);
        merged.put("updated_at", now());
        merged.put("updated_by_user_id", userId);
        repository.saveJsonSetting(AdminOpsRepository.KEY_RUNTIME_LOG_CONFIG, merged);
        return merged;
    }

    public Map<String, Object> resetRuntimeLogConfig() {
        repository.deleteSetting(AdminOpsRepository.KEY_RUNTIME_LOG_CONFIG);
        return defaultRuntimeLogConfig(null);
    }

    public Map<String, Object> getAdvancedSettings() {
        Map<String, Object> stored = repository.loadJsonSetting(AdminOpsRepository.KEY_ADVANCED_SETTINGS);
        if (!stored.isEmpty()) {
            return mergeAdvancedSettings(stored);
        }
        return defaultAdvancedSettings();
    }

    public Map<String, Object> updateAdvancedSettings(Map<String, Object> request) {
        Map<String, Object> merged = mergeAdvancedSettings(request);
        repository.saveJsonSetting(AdminOpsRepository.KEY_ADVANCED_SETTINGS, merged);
        return merged;
    }

    public Map<String, Object> getMetricThresholds() {
        Map<String, Object> stored = repository.loadJsonSetting(AdminOpsRepository.KEY_METRIC_THRESHOLDS);
        if (!stored.isEmpty()) {
            return mergeMetricThresholds(stored);
        }
        return metricThresholds();
    }

    public Map<String, Object> updateMetricThresholds(Map<String, Object> request) {
        Map<String, Object> merged = mergeMetricThresholds(request);
        repository.saveJsonSetting(AdminOpsRepository.KEY_METRIC_THRESHOLDS, merged);
        return merged;
    }

    public PageResponse<Map<String, Object>> listSystemLogs(Map<String, Object> filter, Integer page, Integer pageSize) {
        return repository.listSystemLogs(normalizeTimeRangeFilter(filter, "30d"), safePage(page), safePageSize(pageSize));
    }

    public long cleanupSystemLogs(Map<String, Object> request, Long operatorUserId) {
        return repository.cleanupSystemLogs(normalizeTimeRangeFilter(request, "30d"), operatorUserId);
    }

    public Map<String, Object> getSystemLogSinkHealth() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("queue_depth", 0);
        payload.put("queue_capacity", 0);
        payload.put("dropped_count", 0L);
        payload.put("write_failed_count", 0L);
        payload.put("written_count", 0L);
        payload.put("avg_write_delay_ms", 0.0d);
        payload.put("last_error", "");
        return payload;
    }

    public PageResponse<Map<String, Object>> listErrorLogs(Map<String, Object> filter, Integer page, Integer pageSize, boolean upstreamOnly) {
        requireMonitoringEnabled();
        return repository.listErrorLogs(normalizeTimeRangeFilter(filter, "1h"), safePage(page), safePageSizeForErrors(pageSize), upstreamOnly);
    }

    public PageResponse<Map<String, Object>> listRequestDetails(Map<String, Object> filter, Integer page, Integer pageSize) {
        requireMonitoringEnabled();
        Map<String, Object> normalized = normalizeRequestDetailsFilter(filter);
        normalized = normalizeTimeRangeFilter(normalized, "1h");
        return repository.listRequestDetails(normalized, safePage(page), safeRequestDetailsPageSize(pageSize));
    }

    public Map<String, Object> getErrorDetail(long id) {
        requireMonitoringEnabled();
        Map<String, Object> payload = repository.getErrorLog(id);
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "error not found");
        }
        return payload;
    }

    public PageResponse<Map<String, Object>> listRequestErrorUpstreamErrors(long id, Map<String, Object> filter, Integer page, Integer pageSize) {
        requireMonitoringEnabled();
        return repository.listRequestErrorUpstreamErrors(id, normalizeTimeRangeFilter(filter, "30d"), safePage(page), safePageSizeForErrors(pageSize));
    }

    public boolean updateErrorResolved(long id, boolean resolved, Long operatorUserId) {
        requireMonitoringEnabled();
        if (!repository.updateErrorResolved(id, resolved, operatorUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "error not found");
        }
        return true;
    }

    public List<Map<String, Object>> listRetryAttempts(long errorId, Integer limit) {
        requireMonitoringEnabled();
        return repository.listRetryAttempts(errorId, limit == null ? 50 : limit);
    }

    public Map<String, Object> retryClientError(long errorId, long requestedByUserId) {
        return retryErrorInternal(errorId, requestedByUserId, RETRY_MODE_CLIENT, RETRY_MODE_CLIENT, null, null);
    }

    public Map<String, Object> retryUpstreamError(long errorId, long requestedByUserId) {
        return retryErrorInternal(errorId, requestedByUserId, RETRY_MODE_UPSTREAM, RETRY_MODE_UPSTREAM, null, null);
    }

    public Map<String, Object> retryError(long errorId, long requestedByUserId, String mode, Long pinnedAccountId) {
        String normalizedMode = blankToDefault(mode, RETRY_MODE_CLIENT).toLowerCase();
        if (!RETRY_MODE_CLIENT.equals(normalizedMode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "upstream retry is not supported on this endpoint");
        }
        return retryErrorInternal(errorId, requestedByUserId, RETRY_MODE_CLIENT, RETRY_MODE_CLIENT, pinnedAccountId, null);
    }

    public Map<String, Object> retryUpstreamEvent(long errorId, int idx, long requestedByUserId) {
        requireMonitoringEnabled();
        if (idx < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid upstream idx");
        }
        Map<String, Object> errorLog = requireRetryableErrorLog(errorId);
        List<Map<String, Object>> events = parseUpstreamEvents((String) errorLog.get("upstream_errors"));
        if (idx >= events.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "upstream idx out of range");
        }
        Map<String, Object> event = events.get(idx);
        if (event == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "upstream event missing");
        }
        Long accountId = castLong(event.get("account_id"));
        if (accountId == null || accountId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "account_id is required for upstream retry");
        }
        String upstreamRequestBody = blankToEmpty((String) event.get("upstream_request_body"));
        if (upstreamRequestBody.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No upstream request body found to retry");
        }
        Map<String, Object> override = new LinkedHashMap<>(errorLog);
        override.put("request_body", upstreamRequestBody);
        return retryErrorInternal(errorId, requestedByUserId, RETRY_MODE_UPSTREAM_EVENT, RETRY_MODE_UPSTREAM, accountId, override);
    }

    private Map<String, Object> retryErrorInternal(
            long errorId,
            long requestedByUserId,
            String resultMode,
            String executionMode,
            Long pinnedAccountId,
            Map<String, Object> overrideErrorLog
    ) {
        requireMonitoringEnabled();
        Map<String, Object> errorLog = overrideErrorLog == null ? requireRetryableErrorLog(errorId) : overrideErrorLog;
        validateRetryConcurrency(errorId);

        Long effectivePinnedAccountId = pinnedAccountId;
        if (RETRY_MODE_UPSTREAM.equals(executionMode)) {
            if (effectivePinnedAccountId == null || effectivePinnedAccountId <= 0) {
                effectivePinnedAccountId = castLong(errorLog.get("account_id"));
            }
            if (effectivePinnedAccountId == null || effectivePinnedAccountId <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pinned_account_id is required for upstream retry");
            }
        }

        Instant startedAt = Instant.now();
        Map<String, Object> attempt = repository.insertRetryAttemptRunning(errorId, requestedByUserId, resultMode, effectivePinnedAccountId, startedAt);

        AdminOpsRetryExecutor.RetryExecutionResult executionResult = RETRY_MODE_CLIENT.equals(executionMode)
                ? retryExecutor.executeClientRetry(errorLog)
                : retryExecutor.executePinnedRetry(errorLog, effectivePinnedAccountId);

        Instant finishedAt = Instant.now();
        long durationMs = Math.max(0L, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
        boolean success = executionResult.success();
        String status = success ? RETRY_STATUS_SUCCEEDED : RETRY_STATUS_FAILED;
        Long attemptId = castLong(attempt.get("id"));
        repository.updateRetryAttempt(
                attemptId == null ? 0L : attemptId,
                status,
                finishedAt,
                durationMs,
                success,
                executionResult.httpStatusCode() <= 0 ? null : executionResult.httpStatusCode(),
                executionResult.upstreamRequestId(),
                executionResult.usedAccountId(),
                executionResult.responsePreview(),
                executionResult.responseTruncated(),
                executionResult.errorMessage()
        );
        if (success && attemptId != null && attemptId > 0) {
            repository.markErrorResolvedByRetry(errorId, requestedByUserId, attemptId, finishedAt);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attempt_id", attemptId);
        payload.put("mode", resultMode);
        payload.put("status", status);
        payload.put("pinned_account_id", effectivePinnedAccountId);
        payload.put("used_account_id", executionResult.usedAccountId());
        payload.put("http_status_code", executionResult.httpStatusCode());
        payload.put("upstream_request_id", blankToEmpty(executionResult.upstreamRequestId()));
        payload.put("response_preview", blankToEmpty(executionResult.responsePreview()));
        payload.put("response_truncated", executionResult.responseTruncated());
        payload.put("error_message", blankToEmpty(executionResult.errorMessage()));
        payload.put("started_at", toRfc3339(startedAt));
        payload.put("finished_at", toRfc3339(finishedAt));
        payload.put("duration_ms", durationMs);
        return payload;
    }

    public Map<String, Object> createRetryStub(long errorId, long requestedByUserId, String mode, Long pinnedAccountId, String errorMessage) {
        requireMonitoringEnabled();
        Map<String, Object> attempt = repository.createRetryAttempt(errorId, requestedByUserId, mode, pinnedAccountId, errorMessage);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attempt_id", attempt.get("id"));
        payload.put("mode", blankToDefault(mode, "client"));
        payload.put("status", "failed");
        payload.put("pinned_account_id", pinnedAccountId);
        payload.put("used_account_id", null);
        payload.put("http_status_code", 501);
        payload.put("upstream_request_id", "");
        payload.put("response_preview", "");
        payload.put("response_truncated", false);
        payload.put("error_message", blankToDefault(errorMessage, "retry is not implemented in Java yet"));
        payload.put("started_at", attempt.get("started_at"));
        payload.put("finished_at", attempt.get("finished_at"));
        payload.put("duration_ms", 0);
        return payload;
    }

    private Map<String, Object> requireRetryableErrorLog(long errorId) {
        Map<String, Object> payload = repository.getErrorLog(errorId);
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "error not found");
        }
        String requestBody = blankToEmpty((String) payload.get("request_body"));
        if (requestBody.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No request body found to retry");
        }
        return payload;
    }

    private void validateRetryConcurrency(long errorId) {
        Map<String, Object> latest = repository.findLatestRetryAttempt(errorId).orElse(null);
        if (latest == null) {
            return;
        }
        String status = blankToEmpty((String) latest.get("status")).toLowerCase();
        if ("running".equals(status) || "queued".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retry is already in progress for this error");
        }
        Instant lastAttemptAt = parseInstant((String) latest.get("finished_at"));
        if (lastAttemptAt == null) {
            lastAttemptAt = parseInstant((String) latest.get("started_at"));
        }
        if (lastAttemptAt == null) {
            lastAttemptAt = parseInstant((String) latest.get("created_at"));
        }
        if (lastAttemptAt != null && lastAttemptAt.plus(RETRY_MIN_INTERVAL).isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Please wait before retrying this error again");
        }
    }

    private List<Map<String, Object>> parseUpstreamEvents(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return new ArrayList<>(new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
            }));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid upstream_errors");
        }
    }

    public List<Map<String, Object>> listAlertRules() {
        return repository.listAlertRules();
    }

    public Map<String, Object> createAlertRule(Map<String, Object> request) {
        Map<String, Object> normalized = normalizeAlertRule(request);
        return repository.createAlertRule(normalized);
    }

    public Map<String, Object> updateAlertRule(long id, Map<String, Object> request) {
        Map<String, Object> existing = repository.getAlertRule(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert rule not found");
        }
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        if (request != null) {
            merged.putAll(request);
        }
        return repository.updateAlertRule(id, normalizeAlertRule(merged));
    }

    public boolean deleteAlertRule(long id) {
        if (!repository.deleteAlertRule(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert rule not found");
        }
        return true;
    }

    public List<Map<String, Object>> listAlertEvents(
            Integer limit,
            String status,
            String severity,
            Boolean emailSent,
            String timeRange,
            String startTime,
            String endTime,
            String beforeFiredAt,
            Long beforeId,
            String platform,
            Long groupId
    ) {
        TimeWindow timeWindow = resolveDashboardWindow(timeRange, startTime, endTime, Duration.ofHours(1));
        Instant beforeInstant = parseInstant(beforeFiredAt);
        return repository.listAlertEvents(limit, status, severity, emailSent, platform, groupId,
                timeWindow.startInstant(), timeWindow.endInstant(), beforeInstant, beforeId);
    }

    public Map<String, Object> getAlertEvent(long id) {
        Map<String, Object> payload = repository.getAlertEvent(id);
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert event not found");
        }
        return payload;
    }

    public boolean updateAlertEventStatus(long id, String status, Long operatorUserId) {
        if (!List.of("resolved", "manual_resolved", "firing").contains(blankToEmpty(status))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status");
        }
        if (!repository.updateAlertEventStatus(id, status, operatorUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert event not found");
        }
        return true;
    }

    public Map<String, Object> createAlertSilence(Map<String, Object> request, Long createdBy) {
        long ruleId = requiredLong(request, "rule_id");
        String platform = blankToEmpty((String) request.get("platform"));
        if (platform.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "platform is required");
        }
        Instant until = parseInstant((String) request.get("until"));
        if (until == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "until is required");
        }
        return repository.createAlertSilence(
                ruleId,
                platform,
                castLong(request.get("group_id")),
                (String) request.get("region"),
                until,
                (String) request.get("reason"),
                createdBy
        );
    }

    private Map<String, Object> normalizeTimeRangeFilter(Map<String, Object> filter, String defaultRange) {
        Map<String, Object> normalized = new LinkedHashMap<>(filter == null ? Map.of() : filter);
        TimeWindow window = resolveDashboardWindow(
                (String) normalized.get("time_range"),
                (String) normalized.get("start_time"),
                (String) normalized.get("end_time"),
                durationByRange(defaultRange)
        );
        normalized.put("start_time", window.startInstant());
        normalized.put("end_time", window.endInstant());
        return normalized;
    }

    private Map<String, Object> normalizeRequestDetailsFilter(Map<String, Object> filter) {
        Map<String, Object> source = new LinkedHashMap<>(filter == null ? Map.of() : filter);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.putAll(source);

        String kind = blankToEmpty((String) source.get("kind")).toLowerCase();
        if (!kind.isEmpty() && !"all".equals(kind) && !"success".equals(kind) && !"error".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid kind");
        }
        if (!kind.isEmpty()) {
            normalized.put("kind", kind);
        }

        String sort = blankToEmpty((String) source.get("sort")).toLowerCase();
        if (!sort.isEmpty() && !"created_at_desc".equals(sort) && !"duration_desc".equals(sort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid sort");
        }
        if (!sort.isEmpty()) {
            normalized.put("sort", sort);
        }

        copyPositiveLong(source, normalized, "user_id", "Invalid user_id");
        copyPositiveLong(source, normalized, "api_key_id", "Invalid api_key_id");
        copyPositiveLong(source, normalized, "account_id", "Invalid account_id");
        copyPositiveLong(source, normalized, "group_id", "Invalid group_id");
        copyNonNegativeInt(source, normalized, "min_duration_ms", "Invalid min_duration_ms");
        copyNonNegativeInt(source, normalized, "max_duration_ms", "Invalid max_duration_ms");
        return normalized;
    }

    private void requireMonitoringEnabled() {
        if (repository.isMonitoringEnabled()) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ops monitoring is disabled");
    }

    private Map<String, Object> normalizeAlertRule(Map<String, Object> source) {
        Map<String, Object> input = new LinkedHashMap<>(source == null ? Map.of() : source);
        String name = blankToEmpty((String) input.get("name"));
        String metricType = blankToEmpty((String) input.get("metric_type"));
        String operator = blankToEmpty((String) input.get("operator"));
        String severity = blankToDefault((String) input.get("severity"), "P2");
        double threshold = requiredDouble(input, "threshold");
        int windowMinutes = requiredInt(input, "window_minutes", 1);
        int sustainedMinutes = requiredInt(input, "sustained_minutes", 1);
        int cooldownMinutes = requiredInt(input, "cooldown_minutes", 0);

        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (!VALID_ALERT_METRICS.contains(metricType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metric_type is invalid");
        }
        if (!VALID_ALERT_OPERATORS.contains(operator)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operator is invalid");
        }
        if (!VALID_ALERT_SEVERITIES.contains(severity)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "severity is invalid");
        }
        if (List.of("success_rate", "error_rate", "upstream_error_rate", "cpu_usage_percent",
                "memory_usage_percent", "group_available_ratio", "group_rate_limit_ratio",
                "account_error_ratio").contains(metricType) && (threshold < 0 || threshold > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "threshold must be between 0 and 100");
        }
        if (!List.of(1, 5, 60).contains(windowMinutes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "window_minutes must be one of 1, 5, 60");
        }
        if (sustainedMinutes < 1 || sustainedMinutes > 1440) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sustained_minutes must be between 1 and 1440");
        }
        if (cooldownMinutes < 0 || cooldownMinutes > 1440) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cooldown_minutes must be between 0 and 1440");
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("name", name);
        normalized.put("description", blankToNull((String) input.get("description")));
        normalized.put("enabled", input.get("enabled") == null || Boolean.TRUE.equals(input.get("enabled")));
        normalized.put("metric_type", metricType);
        normalized.put("operator", operator);
        normalized.put("threshold", threshold);
        normalized.put("window_minutes", windowMinutes);
        normalized.put("sustained_minutes", sustainedMinutes);
        normalized.put("severity", severity);
        normalized.put("cooldown_minutes", cooldownMinutes);
        normalized.put("notify_email", input.get("notify_email") == null || Boolean.TRUE.equals(input.get("notify_email")));
        normalized.put("filters", input.getOrDefault("filters", Map.of()));
        return normalized;
    }

    private Map<String, Object> defaultEmailNotificationConfig() {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("enabled", false);
        alert.put("recipients", List.of());
        alert.put("min_severity", "");
        alert.put("rate_limit_per_hour", 0);
        alert.put("batching_window_seconds", 0);
        alert.put("include_resolved_alerts", false);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("enabled", false);
        report.put("recipients", List.of());
        report.put("daily_summary_enabled", false);
        report.put("daily_summary_schedule", "0 9 * * *");
        report.put("weekly_summary_enabled", false);
        report.put("weekly_summary_schedule", "0 9 * * 1");
        report.put("error_digest_enabled", false);
        report.put("error_digest_schedule", "0 9 * * *");
        report.put("error_digest_min_count", 0);
        report.put("account_health_enabled", false);
        report.put("account_health_schedule", "0 9 * * *");
        report.put("account_health_error_rate_threshold", 0.0d);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alert", alert);
        payload.put("report", report);
        return payload;
    }

    private Map<String, Object> mergeEmailNotificationConfig(Map<String, Object> source) {
        Map<String, Object> payload = defaultEmailNotificationConfig();
        copyNestedMap(payload, source, "alert");
        copyNestedMap(payload, source, "report");
        return payload;
    }

    private Map<String, Object> defaultAlertRuntimeSettings() {
        Map<String, Object> distributedLock = new LinkedHashMap<>();
        distributedLock.put("enabled", true);
        distributedLock.put("key", "ops:alert:evaluator:leader");
        distributedLock.put("ttl_seconds", 30);

        Map<String, Object> silencing = new LinkedHashMap<>();
        silencing.put("enabled", false);
        silencing.put("global_until_rfc3339", "");
        silencing.put("global_reason", "");
        silencing.put("entries", List.of());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("evaluation_interval_seconds", 60);
        payload.put("distributed_lock", distributedLock);
        payload.put("silencing", silencing);
        payload.put("thresholds", getMetricThresholds());
        return payload;
    }

    private Map<String, Object> mergeAlertRuntimeSettings(Map<String, Object> source) {
        Map<String, Object> payload = defaultAlertRuntimeSettings();
        if (source != null && source.containsKey("evaluation_interval_seconds")) {
            payload.put("evaluation_interval_seconds", requiredInt(source, "evaluation_interval_seconds", 60));
        }
        copyNestedMap(payload, source, "distributed_lock");
        copyNestedMap(payload, source, "silencing");
        if (source != null && source.get("thresholds") instanceof Map<?, ?> thresholds) {
            payload.put("thresholds", mergeMetricThresholds(castMap(thresholds)));
        }
        return payload;
    }

    private Map<String, Object> defaultRuntimeLogConfig(Long userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("level", "info");
        payload.put("enable_sampling", false);
        payload.put("sampling_initial", 100);
        payload.put("sampling_thereafter", 100);
        payload.put("caller", true);
        payload.put("stacktrace_level", "error");
        payload.put("retention_days", 30);
        payload.put("source", "java-default");
        payload.put("updated_at", now());
        payload.put("updated_by_user_id", userId);
        return payload;
    }

    private Map<String, Object> mergeRuntimeLogConfig(Map<String, Object> source) {
        Map<String, Object> payload = defaultRuntimeLogConfig(null);
        if (source != null) {
            payload.putAll(source);
        }
        return payload;
    }

    private Map<String, Object> defaultAdvancedSettings() {
        Map<String, Object> dataRetention = new LinkedHashMap<>();
        dataRetention.put("cleanup_enabled", false);
        dataRetention.put("cleanup_schedule", "0 2 * * *");
        dataRetention.put("error_log_retention_days", 30);
        dataRetention.put("minute_metrics_retention_days", 7);
        dataRetention.put("hourly_metrics_retention_days", 30);

        Map<String, Object> aggregation = new LinkedHashMap<>();
        aggregation.put("aggregation_enabled", false);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data_retention", dataRetention);
        payload.put("aggregation", aggregation);
        payload.put("ignore_count_tokens_errors", false);
        payload.put("ignore_context_canceled", false);
        payload.put("ignore_no_available_accounts", false);
        payload.put("ignore_invalid_api_key_errors", false);
        payload.put("ignore_insufficient_balance_errors", false);
        payload.put("display_openai_token_stats", false);
        payload.put("display_alert_events", false);
        payload.put("auto_refresh_enabled", false);
        payload.put("auto_refresh_interval_seconds", 30);
        return payload;
    }

    private Map<String, Object> mergeAdvancedSettings(Map<String, Object> source) {
        Map<String, Object> payload = defaultAdvancedSettings();
        copyNestedMap(payload, source, "data_retention");
        copyNestedMap(payload, source, "aggregation");
        if (source != null) {
            for (String key : List.of(
                    "ignore_count_tokens_errors",
                    "ignore_context_canceled",
                    "ignore_no_available_accounts",
                    "ignore_invalid_api_key_errors",
                    "ignore_insufficient_balance_errors",
                    "display_openai_token_stats",
                    "display_alert_events",
                    "auto_refresh_enabled",
                    "auto_refresh_interval_seconds"
            )) {
                if (source.containsKey(key)) {
                    payload.put(key, source.get(key));
                }
            }
        }
        return payload;
    }

    private Map<String, Object> metricThresholds() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sla_percent_min", 99.5d);
        payload.put("ttft_p99_ms_max", 500.0d);
        payload.put("request_error_rate_percent_max", 5.0d);
        payload.put("upstream_error_rate_percent_max", 5.0d);
        return payload;
    }

    private Map<String, Object> mergeMetricThresholds(Map<String, Object> source) {
        Map<String, Object> payload = metricThresholds();
        if (source != null) {
            payload.putAll(source);
        }
        return payload;
    }

    private void copyNestedMap(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source == null || !(source.get(key) instanceof Map<?, ?> sourceMap) || !(target.get(key) instanceof Map<?, ?> targetMap)) {
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>(castMap(targetMap));
        merged.putAll(castMap(sourceMap));
        target.put(key, merged);
    }

    private Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            payload.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return payload;
    }

    private Map<String, Object> zeroRateSummary() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("current", 0.0d);
        payload.put("peak", 0.0d);
        payload.put("avg", 0.0d);
        return payload;
    }

    private Map<String, Object> emptyPercentiles() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("p50_ms", null);
        payload.put("p90_ms", null);
        payload.put("p95_ms", null);
        payload.put("p99_ms", null);
        payload.put("avg_ms", null);
        payload.put("max_ms", null);
        return payload;
    }

    private TimeWindow resolveRealtimeWindow(String window) {
        String normalized = blankToDefault(window, "1min").trim().toLowerCase();
        Duration duration = switch (normalized) {
            case "5min", "5m" -> Duration.ofMinutes(5);
            case "30min", "30m" -> Duration.ofMinutes(30);
            case "1h", "60m", "60min" -> Duration.ofHours(1);
            default -> Duration.ofMinutes(1);
        };
        String label = switch (normalized) {
            case "5min", "5m" -> "5min";
            case "30min", "30m" -> "30min";
            case "1h", "60m", "60min" -> "1h";
            default -> "1min";
        };
        Instant end = Instant.now();
        Instant start = end.minus(duration);
        return new TimeWindow(start, end, duration, label);
    }

    private TimeWindow resolveDashboardWindow(String timeRange, String startTime, String endTime, Duration defaultDuration) {
        Instant parsedStart = parseInstant(startTime);
        Instant parsedEnd = parseInstant(endTime);
        if (parsedStart != null && parsedEnd != null && !parsedEnd.isBefore(parsedStart)) {
            return new TimeWindow(parsedStart, parsedEnd, Duration.between(parsedStart, parsedEnd), null);
        }

        Duration duration = switch (blankToDefault(timeRange, "").trim().toLowerCase()) {
            case "5m" -> Duration.ofMinutes(5);
            case "30m" -> Duration.ofMinutes(30);
            case "6h" -> Duration.ofHours(6);
            case "24h" -> Duration.ofHours(24);
            case "7d" -> Duration.ofDays(7);
            case "15d" -> Duration.ofDays(15);
            case "30d" -> Duration.ofDays(30);
            case "1d" -> Duration.ofDays(1);
            default -> defaultDuration;
        };
        Instant end = Instant.now();
        Instant start = end.minus(duration);
        return new TimeWindow(start, end, duration, null);
    }

    private Duration durationByRange(String range) {
        return switch (blankToDefault(range, "").trim().toLowerCase()) {
            case "5m" -> Duration.ofMinutes(5);
            case "30m" -> Duration.ofMinutes(30);
            case "6h" -> Duration.ofHours(6);
            case "24h" -> Duration.ofHours(24);
            case "7d" -> Duration.ofDays(7);
            case "15d" -> Duration.ofDays(15);
            case "30d" -> Duration.ofDays(30);
            case "1d" -> Duration.ofDays(1);
            default -> Duration.ofHours(1);
        };
    }

    private Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(raw.trim()).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String bucketLabel(Duration window) {
        if (window.compareTo(Duration.ofHours(2)) <= 0) {
            return "1m";
        }
        if (window.compareTo(Duration.ofHours(24)) <= 0) {
            return "5m";
        }
        return "1h";
    }

    private int bucketSeconds(Duration window) {
        if (window.compareTo(Duration.ofHours(2)) <= 0) {
            return 60;
        }
        if (window.compareTo(Duration.ofHours(24)) <= 0) {
            return 300;
        }
        return 3600;
    }

    private Long positiveGroupId(Long groupId) {
        return groupId == null || groupId <= 0 ? null : groupId;
    }

    private int safePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private int safePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    private int safeRequestDetailsPageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }

    private int safePageSizeForErrors(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 500);
    }

    private String now() {
        return toRfc3339(Instant.now());
    }

    private String toRfc3339(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).toString();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        String trimmed = blankToEmpty(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void copyPositiveLong(Map<String, Object> source, Map<String, Object> target, String key, String errorMessage) {
        String raw = blankToEmpty((String) source.get(key));
        if (raw.isEmpty()) {
            return;
        }
        try {
            long parsed = Long.parseLong(raw);
            if (parsed <= 0) {
                throw new NumberFormatException("must be positive");
            }
            target.put(key, parsed);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
    }

    private void copyNonNegativeInt(Map<String, Object> source, Map<String, Object> target, String key, String errorMessage) {
        String raw = blankToEmpty((String) source.get(key));
        if (raw.isEmpty()) {
            return;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < 0) {
                throw new NumberFormatException("must be non-negative");
            }
            target.put(key, parsed);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
    }

    private long requiredLong(Map<String, Object> input, String key) {
        Long value = castLong(input == null ? null : input.get(key));
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required");
        }
        return value;
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

    private int requiredInt(Map<String, Object> input, String key, int fallback) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String raw && !raw.isBlank()) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is invalid");
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is invalid");
    }

    private double requiredDouble(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String raw && !raw.isBlank()) {
            try {
                return Double.parseDouble(raw.trim());
            } catch (NumberFormatException ignored) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is invalid");
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required");
    }

    private record TimeWindow(Instant startInstant, Instant endInstant, Duration duration, String label) {
    }
}
