package org.apiprivaterouter.javabackend.userusage.service;

import org.apiprivaterouter.javabackend.admin.dashboard.model.BatchApiKeysUsageResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.ModelStatsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.TrendResponse;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.userkeys.service.UserApiKeyService;
import org.apiprivaterouter.javabackend.userusage.model.BatchApiKeysUsageRequest;
import org.apiprivaterouter.javabackend.userusage.model.UserDashboardStatsResponse;
import org.apiprivaterouter.javabackend.userusage.model.UserUsageLogResponse;
import org.apiprivaterouter.javabackend.userusage.model.UserUsageStatsResponse;
import org.apiprivaterouter.javabackend.userusage.repository.UserUsageRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;

@Service
public class UserUsageService {

    private final UserUsageRepository repository;
    private final UserApiKeyService userApiKeyService;

    public UserUsageService(UserUsageRepository repository, UserApiKeyService userApiKeyService) {
        this.repository = repository;
        this.userApiKeyService = userApiKeyService;
    }

    public PageResponse<UserUsageLogResponse> listUsageLogs(
            CurrentUser currentUser,
            int page,
            int pageSize,
            Long apiKeyId,
            String model,
            String requestType,
            Boolean stream,
            Integer billingType,
            String startDate,
            String endDate,
            String timezone,
            String sortBy,
            String sortOrder
    ) {
        Long normalizedApiKeyId = normalizeApiKeyId(currentUser, apiKeyId);
        return repository.listUsageLogs(
                currentUser.userId(),
                page,
                pageSize,
                normalizedApiKeyId,
                model,
                requestType,
                stream,
                billingType,
                startDate,
                endDate,
                timezone,
                sortBy,
                sortOrder
        );
    }

    public UserUsageLogResponse getById(CurrentUser currentUser, long id) {
        return repository.findUsageLogByIdForUser(id, currentUser.userId())
                .orElseThrow(() -> new HttpStatusException(404, "usage log not found"));
    }

    public UserUsageStatsResponse getStats(
            CurrentUser currentUser,
            String period,
            Long apiKeyId,
            String model,
            String requestType,
            Boolean stream,
            Integer billingType,
            String startDate,
            String endDate,
            String timezone
    ) {
        Long normalizedApiKeyId = normalizeApiKeyId(currentUser, apiKeyId);
        return repository.getStats(
                currentUser.userId(),
                normalizedApiKeyId,
                model,
                requestType,
                stream,
                period,
                startDate,
                endDate,
                timezone,
                billingType
        );
    }

    public UserDashboardStatsResponse getDashboardStats(CurrentUser currentUser) {
        return repository.getDashboardStats(currentUser.userId());
    }

    public TrendResponse getTrend(CurrentUser currentUser, String startDate, String endDate, String granularity, String timezone) {
        return repository.getTrend(currentUser.userId(), startDate, endDate, granularity, timezone);
    }

    public ModelStatsResponse getModelStats(CurrentUser currentUser, String startDate, String endDate, String timezone) {
        return repository.getModelStats(currentUser.userId(), startDate, endDate, timezone);
    }

    public BatchApiKeysUsageResponse getBatchApiKeysUsage(CurrentUser currentUser, BatchApiKeysUsageRequest request, String timezone) {
        List<Long> apiKeyIds = normalizeApiKeyIds(request.api_key_ids());
        if (apiKeyIds.isEmpty()) {
            return new BatchApiKeysUsageResponse(java.util.Map.of());
        }
        if (apiKeyIds.size() > 100) {
            throw new IllegalArgumentException("too many api_key_ids");
        }
        for (Long apiKeyId : apiKeyIds) {
            userApiKeyService.getById(currentUser, apiKeyId);
        }
        return repository.getBatchApiKeysUsage(apiKeyIds, timezone);
    }

    private Long normalizeApiKeyId(CurrentUser currentUser, Long apiKeyId) {
        if (apiKeyId == null || apiKeyId <= 0) {
            return null;
        }
        userApiKeyService.getById(currentUser, apiKeyId);
        return apiKeyId;
    }

    private List<Long> normalizeApiKeyIds(List<Long> apiKeyIds) {
        if (apiKeyIds == null || apiKeyIds.isEmpty()) {
            return List.of();
        }
        return new LinkedHashSet<>(apiKeyIds.stream()
                .filter(id -> id != null && id > 0)
                .toList()).stream().toList();
    }
}
