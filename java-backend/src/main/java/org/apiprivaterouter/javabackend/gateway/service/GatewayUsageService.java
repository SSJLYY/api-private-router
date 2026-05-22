package org.apiprivaterouter.javabackend.gateway.service;

import org.apiprivaterouter.javabackend.admin.dashboard.model.ModelStatsResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.gateway.model.GatewayQuotaResponse;
import org.apiprivaterouter.javabackend.gateway.model.GatewayRateLimitResponse;
import org.apiprivaterouter.javabackend.gateway.model.GatewaySubscriptionUsageResponse;
import org.apiprivaterouter.javabackend.gateway.model.GatewayUsageBucket;
import org.apiprivaterouter.javabackend.gateway.model.GatewayUsageResponse;
import org.apiprivaterouter.javabackend.gateway.model.GatewayUsageStatsBlock;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayApiKeyRuntimeView;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewaySubscriptionSummary;
import org.apiprivaterouter.javabackend.userusage.model.UserDashboardStatsResponse;
import org.apiprivaterouter.javabackend.userusage.service.UserUsageService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class GatewayUsageService {

    private final UserUsageService userUsageService;

    public GatewayUsageService(UserUsageService userUsageService) {
        this.userUsageService = userUsageService;
    }

    public GatewayUsageResponse buildUsageResponse(GatewayRuntimeContext runtimeContext, String startDate, String endDate) {
        GatewayApiKeyRuntimeView apiKey = runtimeContext.apiKey();
        CurrentUser currentUser = new CurrentUser(runtimeContext.user().userId(), runtimeContext.user().email(), runtimeContext.user().role(), 0);
        UserDashboardStatsResponse stats = userUsageService.getDashboardStats(currentUser);
        ModelStatsResponse modelStats = userUsageService.getModelStats(currentUser, startDate, endDate, null);
        GatewayUsageStatsBlock usage = toUsageStats(stats);

        boolean quotaLimited = apiKey.quota() > 0 || hasRateLimits(apiKey);
        if (quotaLimited) {
            return new GatewayUsageResponse(
                    "quota_limited",
                    isKeyValid(apiKey.status()),
                    apiKey.status(),
                    null,
                    apiKey.quota() > 0 ? quotaRemaining(apiKey) : null,
                    apiKey.quota() > 0 ? "USD" : null,
                    null,
                    apiKey.quota() > 0 ? new GatewayQuotaResponse(apiKey.quota(), apiKey.quotaUsed(), quotaRemaining(apiKey), "USD") : null,
                    buildRateLimits(apiKey),
                    usage,
                    isModelStatsEmpty(modelStats) ? null : modelStats,
                    null,
                    apiKey.expiresAt(),
                    calculateDaysUntilExpiry(apiKey.expiresAt())
            );
        }

        if (apiKey.group() != null && "subscription".equalsIgnoreCase(apiKey.group().subscriptionType())) {
            GatewaySubscriptionSummary subscription = runtimeContext.subscription();
            return new GatewayUsageResponse(
                    "unrestricted",
                    true,
                    null,
                    apiKey.group().name(),
                    subscription == null ? null : calculateSubscriptionRemaining(apiKey, subscription),
                    "USD",
                    null,
                    null,
                    null,
                    usage,
                    isModelStatsEmpty(modelStats) ? null : modelStats,
                    subscription == null ? null : new GatewaySubscriptionUsageResponse(
                            subscription.dailyUsageUsd(),
                            subscription.weeklyUsageUsd(),
                            subscription.monthlyUsageUsd(),
                            apiKey.group().dailyLimitUsd(),
                            apiKey.group().weeklyLimitUsd(),
                            apiKey.group().monthlyLimitUsd(),
                            subscription.expiresAt()
                    ),
                    subscription == null ? null : subscription.expiresAt(),
                    calculateDaysUntilExpiry(subscription == null ? null : subscription.expiresAt())
            );
        }

        return new GatewayUsageResponse(
                "unrestricted",
                true,
                null,
                "钱包余额",
                runtimeContext.user().balance(),
                "USD",
                runtimeContext.user().balance(),
                null,
                null,
                usage,
                isModelStatsEmpty(modelStats) ? null : modelStats,
                null,
                null,
                null
        );
    }

    private GatewayUsageStatsBlock toUsageStats(UserDashboardStatsResponse stats) {
        return new GatewayUsageStatsBlock(
                new GatewayUsageBucket(
                        stats.today_requests(),
                        stats.today_input_tokens(),
                        stats.today_output_tokens(),
                        stats.today_cache_creation_tokens(),
                        stats.today_cache_read_tokens(),
                        stats.today_tokens(),
                        stats.today_cost(),
                        stats.today_actual_cost()
                ),
                new GatewayUsageBucket(
                        stats.total_requests(),
                        stats.total_input_tokens(),
                        stats.total_output_tokens(),
                        stats.total_cache_creation_tokens(),
                        stats.total_cache_read_tokens(),
                        stats.total_tokens(),
                        stats.total_cost(),
                        stats.total_actual_cost()
                ),
                stats.average_duration_ms(),
                stats.rpm(),
                stats.tpm()
        );
    }

    private boolean isKeyValid(String status) {
        return "active".equalsIgnoreCase(status)
                || "quota_exhausted".equalsIgnoreCase(status)
                || "expired".equalsIgnoreCase(status);
    }

    private boolean hasRateLimits(GatewayApiKeyRuntimeView apiKey) {
        return apiKey.rateLimit5h() > 0 || apiKey.rateLimit1d() > 0 || apiKey.rateLimit7d() > 0;
    }

    private double quotaRemaining(GatewayApiKeyRuntimeView apiKey) {
        return Math.max(0, apiKey.quota() - apiKey.quotaUsed());
    }

    private List<GatewayRateLimitResponse> buildRateLimits(GatewayApiKeyRuntimeView apiKey) {
        List<GatewayRateLimitResponse> result = new ArrayList<>();
        if (apiKey.rateLimit5h() > 0) {
            result.add(new GatewayRateLimitResponse("5h", apiKey.rateLimit5h(), apiKey.usage5h(), Math.max(0, apiKey.rateLimit5h() - apiKey.usage5h()), apiKey.window5hStart(), apiKey.reset5hAt()));
        }
        if (apiKey.rateLimit1d() > 0) {
            result.add(new GatewayRateLimitResponse("1d", apiKey.rateLimit1d(), apiKey.usage1d(), Math.max(0, apiKey.rateLimit1d() - apiKey.usage1d()), apiKey.window1dStart(), apiKey.reset1dAt()));
        }
        if (apiKey.rateLimit7d() > 0) {
            result.add(new GatewayRateLimitResponse("7d", apiKey.rateLimit7d(), apiKey.usage7d(), Math.max(0, apiKey.rateLimit7d() - apiKey.usage7d()), apiKey.window7dStart(), apiKey.reset7dAt()));
        }
        return result.isEmpty() ? null : List.copyOf(result);
    }

    private Double calculateSubscriptionRemaining(GatewayApiKeyRuntimeView apiKey, GatewaySubscriptionSummary subscription) {
        List<Double> remaining = new ArrayList<>();
        if (apiKey.group().dailyLimitUsd() != null) {
            double value = apiKey.group().dailyLimitUsd() - subscription.dailyUsageUsd();
            if (value <= 0) {
                return 0.0;
            }
            remaining.add(value);
        }
        if (apiKey.group().weeklyLimitUsd() != null) {
            double value = apiKey.group().weeklyLimitUsd() - subscription.weeklyUsageUsd();
            if (value <= 0) {
                return 0.0;
            }
            remaining.add(value);
        }
        if (apiKey.group().monthlyLimitUsd() != null) {
            double value = apiKey.group().monthlyLimitUsd() - subscription.monthlyUsageUsd();
            if (value <= 0) {
                return 0.0;
            }
            remaining.add(value);
        }
        if (remaining.isEmpty()) {
            return -1.0;
        }
        return remaining.stream().min(Double::compareTo).orElse(-1.0);
    }

    private Integer calculateDaysUntilExpiry(String expiresAt) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return null;
        }
        try {
            long days = ChronoUnit.DAYS.between(Instant.now(), Instant.parse(expiresAt));
            return (int) days;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isModelStatsEmpty(ModelStatsResponse modelStats) {
        return modelStats == null || modelStats.models() == null || modelStats.models().isEmpty();
    }
}
