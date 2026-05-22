package org.apiprivaterouter.javabackend.admin.dashboard.service;

import org.apiprivaterouter.javabackend.admin.dashboard.model.AdminDashboardStatsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.ApiKeyTrendResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.BatchApiKeysUsageResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.BatchUsersUsageResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.DashboardAggregationBackfillRequest;
import org.apiprivaterouter.javabackend.admin.dashboard.model.DashboardSnapshotV2Response;
import org.apiprivaterouter.javabackend.admin.dashboard.model.GroupStatsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.ModelStatsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.RealtimeMetricsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.TrendResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.UserBreakdownResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.UserSpendingRankingResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.UserTrendResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.repository.AdminDashboardRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Service
public class AdminDashboardService {

    private final AdminDashboardRepository repository;
    private final long startTimeMillis = System.currentTimeMillis();

    public AdminDashboardService(AdminDashboardRepository repository) {
        this.repository = repository;
    }

    public AdminDashboardStatsResponse getStats() {
        long uptimeSeconds = Math.max(0, (System.currentTimeMillis() - startTimeMillis) / 1000L);
        return repository.loadStats(uptimeSeconds);
    }

    public RealtimeMetricsResponse getRealtimeMetrics() {
        return repository.loadRealtimeMetrics();
    }

    public UserSpendingRankingResponse getUserSpendingRanking(String startDate, String endDate, Integer limit, String timezone) {
        ZoneId zoneId = resolveZoneId(timezone);
        LocalDate end = parseDateOrToday(endDate, zoneId);
        LocalDate start = startDate == null || startDate.isBlank() ? end.minusDays(7) : LocalDate.parse(startDate.trim());
        int normalizedLimit = limit == null ? 12 : limit;
        return repository.loadUserSpendingRanking(start, end, zoneId, normalizedLimit);
    }

    public BatchUsersUsageResponse getBatchUsersUsage(java.util.List<Long> userIds, String timezone) {
        return repository.loadBatchUsersUsage(userIds, resolveZoneId(timezone));
    }

    public BatchApiKeysUsageResponse getBatchApiKeysUsage(java.util.List<Long> apiKeyIds, String timezone) {
        return repository.loadBatchApiKeysUsage(apiKeyIds, resolveZoneId(timezone));
    }

    public TrendResponse getUsageTrend(
            String startDate,
            String endDate,
            String granularity,
            Long userId,
            Long apiKeyId,
            String model,
            Long accountId,
            Long groupId,
            String requestType,
            Boolean stream,
            Integer billingType,
            String timezone
    ) {
        QueryWindow window = buildWindow(startDate, endDate, timezone);
        String resolvedGranularity = "hour".equalsIgnoreCase(granularity) ? "hour" : "day";
        return new TrendResponse(
                repository.loadUsageTrend(
                        window.start(),
                        window.endExclusive(),
                        resolvedGranularity,
                        normalizeId(userId),
                        normalizeId(apiKeyId),
                        normalizeId(accountId),
                        normalizeId(groupId),
                        model,
                        normalizeRequestType(requestType),
                        requestType == null ? stream : null,
                        billingType
                ),
                window.startDate().toString(),
                window.endDate().toString(),
                resolvedGranularity
        );
    }

    public ModelStatsResponse getModelStats(
            String startDate,
            String endDate,
            Long userId,
            Long apiKeyId,
            Long accountId,
            Long groupId,
            String requestType,
            Boolean stream,
            Integer billingType,
            String modelSource,
            String timezone
    ) {
        QueryWindow window = buildWindow(startDate, endDate, timezone);
        return new ModelStatsResponse(
                repository.loadModelStats(
                        window.start(),
                        window.endExclusive(),
                        normalizeId(userId),
                        normalizeId(apiKeyId),
                        normalizeId(accountId),
                        normalizeId(groupId),
                        normalizeRequestType(requestType),
                        requestType == null ? stream : null,
                        billingType,
                        modelSource
                ),
                window.startDate().toString(),
                window.endDate().toString()
        );
    }

    public GroupStatsResponse getGroupStats(
            String startDate,
            String endDate,
            Long userId,
            Long apiKeyId,
            Long accountId,
            Long groupId,
            String requestType,
            Boolean stream,
            Integer billingType,
            String timezone
    ) {
        QueryWindow window = buildWindow(startDate, endDate, timezone);
        return new GroupStatsResponse(
                repository.loadGroupStats(
                        window.start(),
                        window.endExclusive(),
                        normalizeId(userId),
                        normalizeId(apiKeyId),
                        normalizeId(accountId),
                        normalizeId(groupId),
                        normalizeRequestType(requestType),
                        requestType == null ? stream : null,
                        billingType
                ),
                window.startDate().toString(),
                window.endDate().toString()
        );
    }

    public UserTrendResponse getUserUsageTrend(String startDate, String endDate, String granularity, Integer limit, String timezone) {
        QueryWindow window = buildWindow(startDate, endDate, timezone);
        String resolvedGranularity = "hour".equalsIgnoreCase(granularity) ? "hour" : "day";
        return new UserTrendResponse(
                repository.loadUserUsageTrend(window.start(), window.endExclusive(), resolvedGranularity, limit == null ? 12 : limit),
                window.startDate().toString(),
                window.endDate().toString(),
                resolvedGranularity
        );
    }

    public ApiKeyTrendResponse getApiKeyUsageTrend(String startDate, String endDate, String granularity, Integer limit, String timezone) {
        QueryWindow window = buildWindow(startDate, endDate, timezone);
        String resolvedGranularity = "hour".equalsIgnoreCase(granularity) ? "hour" : "day";
        return new ApiKeyTrendResponse(
                repository.loadApiKeyUsageTrend(window.start(), window.endExclusive(), resolvedGranularity, limit == null ? 5 : limit),
                window.startDate().toString(),
                window.endDate().toString(),
                resolvedGranularity
        );
    }

    public UserBreakdownResponse getUserBreakdown(
            String startDate,
            String endDate,
            Long groupId,
            String model,
            String modelSource,
            String endpoint,
            String endpointType,
            Long userId,
            Long apiKeyId,
            Long accountId,
            String requestType,
            Boolean stream,
            Integer billingType,
            Integer limit,
            String timezone
    ) {
        QueryWindow window = buildWindow(startDate, endDate, timezone);
        String normalizedRequestType = normalizeRequestType(requestType);
        return new UserBreakdownResponse(
                repository.loadUserBreakdown(
                        window.start(),
                        window.endExclusive(),
                        normalizeId(groupId),
                        model,
                        modelSource,
                        endpoint,
                        endpointType,
                        normalizeId(userId),
                        normalizeId(apiKeyId),
                        normalizeId(accountId),
                        normalizedRequestType,
                        normalizedRequestType == null ? stream : null,
                        billingType,
                        limit == null ? 50 : limit
                ),
                window.startDate().toString(),
                window.endDate().toString()
        );
    }

    public DashboardSnapshotV2Response getSnapshotV2(
            String startDate,
            String endDate,
            String granularity,
            Long userId,
            Long apiKeyId,
            String model,
            Long accountId,
            Long groupId,
            String requestType,
            Boolean stream,
            Integer billingType,
            Boolean includeStats,
            Boolean includeTrend,
            Boolean includeModelStats,
            Boolean includeGroupStats,
            Boolean includeUsersTrend,
            Integer usersTrendLimit,
            String timezone
    ) {
        QueryWindow window = buildWindow(startDate, endDate, timezone);
        String resolvedGranularity = "hour".equalsIgnoreCase(granularity) ? "hour" : "day";
        boolean withStats = includeStats == null || includeStats;
        boolean withTrend = includeTrend == null || includeTrend;
        boolean withModels = includeModelStats == null || includeModelStats;
        boolean withGroups = includeGroupStats != null && includeGroupStats;
        boolean withUsersTrend = includeUsersTrend != null && includeUsersTrend;
        String normalizedRequestType = normalizeRequestType(requestType);
        Boolean resolvedStream = normalizedRequestType == null ? stream : null;
        Long normalizedUserId = normalizeId(userId);
        Long normalizedApiKeyId = normalizeId(apiKeyId);
        Long normalizedAccountId = normalizeId(accountId);
        Long normalizedGroupId = normalizeId(groupId);

        return new DashboardSnapshotV2Response(
                Instant.now().toString(),
                window.startDate().toString(),
                window.endDate().toString(),
                resolvedGranularity,
                withStats ? getStats() : null,
                withTrend ? repository.loadUsageTrend(window.start(), window.endExclusive(), resolvedGranularity, normalizedUserId, normalizedApiKeyId, normalizedAccountId, normalizedGroupId, model, normalizedRequestType, resolvedStream, billingType) : null,
                withModels ? repository.loadModelStats(window.start(), window.endExclusive(), normalizedUserId, normalizedApiKeyId, normalizedAccountId, normalizedGroupId, normalizedRequestType, resolvedStream, billingType, "requested") : null,
                withGroups ? repository.loadGroupStats(window.start(), window.endExclusive(), normalizedUserId, normalizedApiKeyId, normalizedAccountId, normalizedGroupId, normalizedRequestType, resolvedStream, billingType) : null,
                withUsersTrend ? repository.loadUserUsageTrend(window.start(), window.endExclusive(), resolvedGranularity, usersTrendLimit == null ? 12 : usersTrendLimit) : null
        );
    }

    public Map<String, String> backfillAggregation(DashboardAggregationBackfillRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        try {
            Instant start = Instant.parse(request.start().trim());
            Instant end = Instant.parse(request.end().trim());
            if (end.isBefore(start)) {
                throw new IllegalArgumentException("end must be after start");
            }
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("start/end must be RFC3339 timestamps");
        }
        return Map.of("status", "accepted");
    }

    private LocalDate parseDateOrToday(String value, ZoneId zoneId) {
        if (value == null || value.isBlank()) {
            return LocalDate.now(zoneId);
        }
        return LocalDate.parse(value.trim());
    }

    private QueryWindow buildWindow(String startDate, String endDate, String timezone) {
        ZoneId zoneId = resolveZoneId(timezone);
        LocalDate end = parseDateOrToday(endDate, zoneId);
        LocalDate start = startDate == null || startDate.isBlank() ? end.minusDays(7) : LocalDate.parse(startDate.trim());
        return new QueryWindow(
                start,
                end,
                start.atStartOfDay(zoneId).toInstant(),
                end.plusDays(1).atStartOfDay(zoneId).toInstant()
        );
    }

    private ZoneId resolveZoneId(String timezone) {
        try {
            return timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
        } catch (Exception ex) {
            return ZoneId.systemDefault();
        }
    }

    private Long normalizeId(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private String normalizeRequestType(String requestType) {
        if (requestType == null || requestType.isBlank()) {
            return null;
        }
        String normalized = requestType.trim().toLowerCase();
        return switch (normalized) {
            case "sync", "stream", "ws_v2", "unknown" -> normalized;
            default -> null;
        };
    }

    private record QueryWindow(
            LocalDate startDate,
            LocalDate endDate,
            Instant start,
            Instant endExclusive
    ) {
    }
}
