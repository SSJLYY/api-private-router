package org.apiprivaterouter.javabackend.usercenter.controller;

import org.apiprivaterouter.javabackend.admin.platformquota.model.UserPlatformQuotaListResponse;
import org.apiprivaterouter.javabackend.admin.platformquota.model.UserPlatformQuotaResponse;
import org.apiprivaterouter.javabackend.admin.platformquota.repository.UserPlatformQuotaRepository;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class UserPlatformQuotaController {

    private final UserPlatformQuotaRepository quotaRepository;
    private final CurrentUserContext currentUserContext;

    public UserPlatformQuotaController(
            UserPlatformQuotaRepository quotaRepository,
            CurrentUserContext currentUserContext
    ) {
        this.quotaRepository = quotaRepository;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/user/platform-quotas")
    public ApiResponse<UserPlatformQuotaListResponse> getMyPlatformQuotas() {
        long userId = currentUserContext.requireUser().userId();
        List<UserPlatformQuotaResponse> quotas = quotaRepository.listByUserId(userId);
        List<UserPlatformQuotaResponse> lazyZeroed = quotas.stream()
                .map(this::lazyZeroExpiredWindows)
                .toList();
        return ApiResponse.success(new UserPlatformQuotaListResponse(lazyZeroed));
    }

    private UserPlatformQuotaResponse lazyZeroExpiredWindows(UserPlatformQuotaResponse q) {
        Instant now = Instant.now();
        BigDecimal dailyUsage = q.daily_usage_usd();
        String dailyStart = q.daily_window_start();
        if (isWindowExpired(dailyStart, 1)) {
            dailyUsage = BigDecimal.ZERO;
            dailyStart = null;
        }
        BigDecimal weeklyUsage = q.weekly_usage_usd();
        String weeklyStart = q.weekly_window_start();
        if (isWindowExpired(weeklyStart, 7)) {
            weeklyUsage = BigDecimal.ZERO;
            weeklyStart = null;
        }
        BigDecimal monthlyUsage = q.monthly_usage_usd();
        String monthlyStart = q.monthly_window_start();
        if (isWindowExpired(monthlyStart, 30)) {
            monthlyUsage = BigDecimal.ZERO;
            monthlyStart = null;
        }
        return new UserPlatformQuotaResponse(
                q.id(),
                q.user_id(),
                q.platform(),
                q.daily_limit_usd(),
                q.weekly_limit_usd(),
                q.monthly_limit_usd(),
                dailyUsage,
                weeklyUsage,
                monthlyUsage,
                dailyStart,
                weeklyStart,
                monthlyStart,
                q.created_at(),
                q.updated_at()
        );
    }

    private boolean isWindowExpired(String windowStart, int days) {
        if (windowStart == null || windowStart.isBlank()) {
            return false;
        }
        try {
            Instant start = Instant.parse(windowStart);
            Instant resetAt = start.plus(days, ChronoUnit.DAYS);
            return !resetAt.isAfter(Instant.now());
        } catch (Exception ex) {
            return false;
        }
    }
}
