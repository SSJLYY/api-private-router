package org.apiprivaterouter.javabackend.admin.usage.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apiprivaterouter.javabackend.admin.usage.model.AdminUsageLogResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.AdminUsageStatsResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.CreateUsageCleanupTaskRequest;
import org.apiprivaterouter.javabackend.admin.usage.model.SimpleUsageApiKeyResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.SimpleUsageUserResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.UsageCleanupTaskCancelResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.UsageCleanupTaskResponse;
import org.apiprivaterouter.javabackend.admin.usage.repository.AdminUsageRepository;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AdminUsageService {

    private static final Logger log = LoggerFactory.getLogger(AdminUsageService.class);
    private static final List<String> ALLOWED_REQUEST_TYPES = List.of("unknown", "sync", "stream", "ws_v2");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int CLEANUP_BATCH_SIZE = 5000;
    private static final int CLEANUP_MAX_RANGE_DAYS = 31;
    private static final long CLEANUP_STALE_SECONDS = 1800;
    private static final Executor CLEANUP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "api-private-router-usage-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private final AdminUsageRepository repository;
    private final AtomicBoolean cleanupRunning = new AtomicBoolean(false);

    public AdminUsageService(AdminUsageRepository repository) {
        this.repository = repository;
    }

    public PageResponse<AdminUsageLogResponse> listUsageLogs(
            int page,
            int pageSize,
            Long userId,
            Long apiKeyId,
            Long accountId,
            Long groupId,
            String model,
            String requestType,
            Boolean stream,
            Integer billingType,
            String billingMode,
            String startDate,
            String endDate,
            String timezone,
            String sortBy,
            String sortOrder
    ) {
        AdminUsageRepository.UsageFilters filters = buildFilters(
                userId, apiKeyId, accountId, groupId, model, requestType, stream, billingType, billingMode, startDate, endDate, timezone
        );
        return repository.listUsageLogs(normalizePage(page), normalizePageSize(pageSize), filters, sortBy, sortOrder);
    }

    public AdminUsageStatsResponse getStats(
            Long userId,
            Long apiKeyId,
            Long accountId,
            Long groupId,
            String model,
            String requestType,
            Boolean stream,
            String period,
            String startDate,
            String endDate,
            String timezone,
            Integer billingType,
            String billingMode
    ) {
        AdminUsageRepository.UsageFilters filters = buildFilters(
                userId, apiKeyId, accountId, groupId, model, requestType, stream, billingType, billingMode,
                startDate, endDate, timezone, period
        );
        return repository.getStats(filters);
    }

    public List<SimpleUsageUserResponse> searchUsers(String keyword) {
        return repository.searchUsers(keyword);
    }

    public List<SimpleUsageApiKeyResponse> searchApiKeys(Long userId, String keyword) {
        return repository.searchApiKeys(userId, keyword);
    }

    public PageResponse<UsageCleanupTaskResponse> listCleanupTasks(int page, int pageSize) {
        PageResponse<AdminUsageRepository.UsageCleanupTask> raw = repository.listCleanupTasks(normalizePage(page), normalizePageSize(pageSize));
        List<UsageCleanupTaskResponse> items = raw.items().stream().map(this::toCleanupTaskResponse).toList();
        return new PageResponse<>(items, raw.total(), raw.page(), raw.page_size(), raw.pages());
    }

    public UsageCleanupTaskResponse createCleanupTask(CreateUsageCleanupTaskRequest request, long operatorId) {
        if (operatorId <= 0) {
            throw new HttpStatusException(401, "Unauthorized");
        }
        AdminUsageRepository.CleanupTaskFilters filters = buildCleanupFilters(request);
        validateCleanupFilters(filters);
        AdminUsageRepository.UsageCleanupTask task = repository.createCleanupTask(filters, operatorId);
        triggerCleanupExecution();
        return toCleanupTaskResponse(task);
    }

    public UsageCleanupTaskCancelResponse cancelCleanupTask(long taskId, long operatorId) {
        if (operatorId <= 0) {
            throw new HttpStatusException(401, "Unauthorized");
        }
        if (taskId <= 0) {
            throw new IllegalArgumentException("Invalid task id");
        }
        String status = repository.getCleanupTaskStatus(taskId);
        if (status == null) {
            throw new HttpStatusException(404, "cleanup task not found");
        }
        if (AdminUsageRepository.CleanupTaskStatus.CANCELED.value().equals(status)) {
            return new UsageCleanupTaskCancelResponse(taskId, AdminUsageRepository.CleanupTaskStatus.CANCELED.value());
        }
        if (!AdminUsageRepository.CleanupTaskStatus.PENDING.value().equals(status)
                && !AdminUsageRepository.CleanupTaskStatus.RUNNING.value().equals(status)) {
            throw new HttpStatusException(409, "cleanup task cannot be canceled in current status");
        }
        boolean updated = repository.cancelCleanupTask(taskId, operatorId);
        if (!updated) {
            String current = repository.getCleanupTaskStatus(taskId);
            if (AdminUsageRepository.CleanupTaskStatus.CANCELED.value().equals(current)) {
                return new UsageCleanupTaskCancelResponse(taskId, AdminUsageRepository.CleanupTaskStatus.CANCELED.value());
            }
            throw new HttpStatusException(409, "cleanup task cannot be canceled in current status");
        }
        return new UsageCleanupTaskCancelResponse(taskId, AdminUsageRepository.CleanupTaskStatus.CANCELED.value());
    }

    @Scheduled(fixedDelay = 10000L, initialDelay = 10000L)
    public void pollCleanupTasks() {
        triggerCleanupExecution();
    }

    private void triggerCleanupExecution() {
        CLEANUP_EXECUTOR.execute(this::runCleanupOnce);
    }

    private void runCleanupOnce() {
        if (!cleanupRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            AdminUsageRepository.UsageCleanupTask task = repository.claimNextCleanupTask(CLEANUP_STALE_SECONDS);
            if (task == null) {
                return;
            }
            executeCleanupTask(task);
        } catch (Exception ex) {
            log.error("usage cleanup worker failed", ex);
        } finally {
            cleanupRunning.set(false);
        }
    }

    private void executeCleanupTask(AdminUsageRepository.UsageCleanupTask task) {
        long deletedTotal = task.deletedRows();
        Instant startedAt = Instant.now();
        while (true) {
            String status = repository.getCleanupTaskStatus(task.id());
            if (status == null || AdminUsageRepository.CleanupTaskStatus.CANCELED.value().equals(status)) {
                return;
            }
            try {
                long deleted = repository.deleteUsageLogsBatch(task.filters(), CLEANUP_BATCH_SIZE);
                deletedTotal += deleted;
                if (deleted > 0) {
                    repository.updateCleanupTaskProgress(task.id(), deletedTotal);
                }
                if (deleted == 0 || deleted < CLEANUP_BATCH_SIZE) {
                    repository.markCleanupTaskSucceeded(task.id(), deletedTotal);
                    log.info("usage cleanup task {} succeeded, deleted_rows={}, duration={}s",
                            task.id(), deletedTotal, Duration.between(startedAt, Instant.now()).toSeconds());
                    return;
                }
            } catch (Exception ex) {
                repository.markCleanupTaskFailed(task.id(), deletedTotal, ex.getMessage());
                log.error("usage cleanup task {} failed", task.id(), ex);
                return;
            }
        }
    }

    private AdminUsageRepository.UsageFilters buildFilters(
            Long userId,
            Long apiKeyId,
            Long accountId,
            Long groupId,
            String model,
            String requestType,
            Boolean stream,
            Integer billingType,
            String billingMode,
            String startDate,
            String endDate,
            String timezone
    ) {
        return buildFilters(userId, apiKeyId, accountId, groupId, model, requestType, stream, billingType, billingMode, startDate, endDate, timezone, null);
    }

    private AdminUsageRepository.UsageFilters buildFilters(
            Long userId,
            Long apiKeyId,
            Long accountId,
            Long groupId,
            String model,
            String requestType,
            Boolean stream,
            Integer billingType,
            String billingMode,
            String startDate,
            String endDate,
            String timezone,
            String period
    ) {
        String normalizedRequestType = normalizeRequestType(requestType);
        ZoneId zoneId = resolveZoneId(timezone);
        Instant start = null;
        Instant end = null;
        if (startDate != null && !startDate.isBlank() && endDate != null && !endDate.isBlank()) {
            start = LocalDate.parse(startDate.trim()).atStartOfDay(zoneId).toInstant();
            end = LocalDate.parse(endDate.trim()).plusDays(1).atStartOfDay(zoneId).toInstant();
        } else if (period != null && !period.isBlank()) {
            LocalDate today = LocalDate.now(zoneId);
            switch (period.trim().toLowerCase()) {
                case "week" -> start = today.minusDays(7).atStartOfDay(zoneId).toInstant();
                case "month" -> start = today.minusMonths(1).atStartOfDay(zoneId).toInstant();
                default -> start = today.atStartOfDay(zoneId).toInstant();
            }
            end = Instant.now();
        }
        return new AdminUsageRepository.UsageFilters(
                normalizeId(userId),
                normalizeId(apiKeyId),
                normalizeId(accountId),
                normalizeId(groupId),
                normalizeText(model),
                normalizedRequestType,
                normalizedRequestType == null ? stream : null,
                billingType,
                normalizeText(billingMode),
                start,
                end
        );
    }

    private AdminUsageRepository.CleanupTaskFilters buildCleanupFilters(CreateUsageCleanupTaskRequest request) {
        String startDate = normalizeText(request.start_date());
        String endDate = normalizeText(request.end_date());
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("start_date and end_date are required");
        }
        ZoneId zoneId = resolveZoneId(request.timezone());
        Instant startTime = LocalDate.parse(startDate).atStartOfDay(zoneId).toInstant();
        Instant endTime = LocalDate.parse(endDate).plusDays(1).atStartOfDay(zoneId).toInstant().minusMillis(1);
        String requestType = normalizeRequestType(request.request_type());
        Integer billingType = request.billing_type() != null && request.billing_type() < 0 ? null : request.billing_type();
        return new AdminUsageRepository.CleanupTaskFilters(
                startTime,
                endTime,
                normalizeId(request.user_id()),
                normalizeId(request.api_key_id()),
                normalizeId(request.account_id()),
                normalizeId(request.group_id()),
                normalizeText(request.model()),
                requestType,
                requestType == null ? request.stream() : null,
                billingType
        );
    }

    private void validateCleanupFilters(AdminUsageRepository.CleanupTaskFilters filters) {
        if (filters.startTime() == null || filters.endTime() == null) {
            throw new IllegalArgumentException("start_date and end_date are required");
        }
        if (filters.endTime().isBefore(filters.startTime())) {
            throw new IllegalArgumentException("end_date must be after start_date");
        }
        long days = Duration.between(filters.startTime(), filters.endTime()).toDays();
        if (days > CLEANUP_MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("date range exceeds %d days".formatted(CLEANUP_MAX_RANGE_DAYS));
        }
    }

    private UsageCleanupTaskResponse toCleanupTaskResponse(AdminUsageRepository.UsageCleanupTask task) {
        return new UsageCleanupTaskResponse(
                task.id(),
                task.status().value(),
                task.filters().toResponseFilters(),
                task.createdBy(),
                task.deletedRows(),
                task.errorMessage(),
                task.canceledBy(),
                toIsoString(task.canceledAt()),
                toIsoString(task.startedAt()),
                toIsoString(task.finishedAt()),
                toIsoString(task.createdAt()),
                toIsoString(task.updatedAt())
        );
    }

    private Long normalizeId(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeRequestType(String requestType) {
        String normalized = normalizeText(requestType);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase();
        if (!ALLOWED_REQUEST_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("request_type is invalid");
        }
        return normalized;
    }

    private ZoneId resolveZoneId(String timezone) {
        try {
            return timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
        } catch (Exception ex) {
            return ZoneId.systemDefault();
        }
    }

    private String toIsoString(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private int normalizePage(int page) {
        return Math.max(page, 1);
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
