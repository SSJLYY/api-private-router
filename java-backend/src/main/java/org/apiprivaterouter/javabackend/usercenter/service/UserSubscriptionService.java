package org.apiprivaterouter.javabackend.usercenter.service;

import org.apiprivaterouter.javabackend.admin.subscription.model.AdminSubscriptionResponse;
import org.apiprivaterouter.javabackend.admin.subscription.model.SubscriptionProgressResponse;
import org.apiprivaterouter.javabackend.admin.subscription.service.AdminSubscriptionService;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.usercenter.model.UserSubscriptionProgressItemResponse;
import org.apiprivaterouter.javabackend.usercenter.model.UserSubscriptionResponse;
import org.apiprivaterouter.javabackend.usercenter.model.UserSubscriptionSummaryResponse;
import org.apiprivaterouter.javabackend.usercenter.repository.UserSubscriptionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class UserSubscriptionService {

    private final UserSubscriptionRepository repository;
    private final AdminSubscriptionService adminSubscriptionService;

    public UserSubscriptionService(
            UserSubscriptionRepository repository,
            AdminSubscriptionService adminSubscriptionService
    ) {
        this.repository = repository;
        this.adminSubscriptionService = adminSubscriptionService;
    }

    public List<UserSubscriptionResponse> list(CurrentUser currentUser) {
        return repository.listByUser(currentUser.userId()).stream()
                .map(this::toUserSubscription)
                .toList();
    }

    public List<UserSubscriptionResponse> listActive(CurrentUser currentUser) {
        return repository.listActiveByUser(currentUser.userId()).stream()
                .map(this::toUserSubscription)
                .toList();
    }

    public List<UserSubscriptionProgressItemResponse> listProgress(CurrentUser currentUser) {
        return repository.listActiveByUser(currentUser.userId()).stream()
                .map(subscription -> new UserSubscriptionProgressItemResponse(
                        toUserSubscription(subscription),
                        adminSubscriptionService.toProgress(subscription)
                ))
                .toList();
    }

    public SubscriptionProgressResponse getProgress(CurrentUser currentUser, long subscriptionId) {
        AdminSubscriptionResponse subscription = repository.findByIdForUser(subscriptionId, currentUser.userId());
        return adminSubscriptionService.toProgress(subscription);
    }

    public UserSubscriptionSummaryResponse getSummary(CurrentUser currentUser) {
        List<AdminSubscriptionResponse> subscriptions = repository.listActiveByUser(currentUser.userId());
        List<UserSubscriptionResponse> normalized = subscriptions.stream()
                .map(this::toUserSubscription)
                .toList();
        List<UserSubscriptionSummaryResponse.Item> items = normalized.stream()
                .map(this::toSummaryItem)
                .toList();
        double totalUsed = normalized.stream()
                .mapToDouble(UserSubscriptionResponse::monthly_usage_usd)
                .sum();
        return new UserSubscriptionSummaryResponse(items.size(), totalUsed, items);
    }

    private UserSubscriptionResponse toUserSubscription(AdminSubscriptionResponse source) {
        boolean dailyExpired = isWindowExpired(source.daily_window_start(), 1);
        boolean weeklyExpired = isWindowExpired(source.weekly_window_start(), 7);
        boolean monthlyExpired = isWindowExpired(source.monthly_window_start(), 30);
        return new UserSubscriptionResponse(
                source.id(),
                source.user_id(),
                source.group_id(),
                source.starts_at(),
                source.expires_at(),
                normalizeStatus(source.status(), source.expires_at()),
                dailyExpired ? null : source.daily_window_start(),
                weeklyExpired ? null : source.weekly_window_start(),
                monthlyExpired ? null : source.monthly_window_start(),
                dailyExpired ? 0.0 : source.daily_usage_usd(),
                weeklyExpired ? 0.0 : source.weekly_usage_usd(),
                monthlyExpired ? 0.0 : source.monthly_usage_usd(),
                source.created_at(),
                source.updated_at(),
                toGroupSummary(source.group())
        );
    }

    private UserSubscriptionResponse.GroupSummary toGroupSummary(AdminSubscriptionResponse.GroupSummary group) {
        if (group == null) {
            return null;
        }
        return new UserSubscriptionResponse.GroupSummary(
                group.id(),
                group.name(),
                group.description(),
                group.platform(),
                group.rate_multiplier(),
                group.status(),
                group.subscription_type(),
                group.daily_limit_usd(),
                group.weekly_limit_usd(),
                group.monthly_limit_usd()
        );
    }

    private UserSubscriptionSummaryResponse.Item toSummaryItem(UserSubscriptionResponse source) {
        UserSubscriptionResponse.GroupSummary group = source.group();
        Double dailyProgress = calculateProgress(source.daily_usage_usd(), group == null ? null : group.daily_limit_usd());
        Double weeklyProgress = calculateProgress(source.weekly_usage_usd(), group == null ? null : group.weekly_limit_usd());
        Double monthlyProgress = calculateProgress(source.monthly_usage_usd(), group == null ? null : group.monthly_limit_usd());
        return new UserSubscriptionSummaryResponse.Item(
                source.id(),
                source.group_id(),
                group == null ? "" : group.name(),
                source.status(),
                source.daily_usage_usd(),
                group == null || group.daily_limit_usd() == null ? 0.0 : group.daily_limit_usd(),
                source.weekly_usage_usd(),
                group == null || group.weekly_limit_usd() == null ? 0.0 : group.weekly_limit_usd(),
                source.monthly_usage_usd(),
                group == null || group.monthly_limit_usd() == null ? 0.0 : group.monthly_limit_usd(),
                source.expires_at(),
                dailyProgress,
                weeklyProgress,
                monthlyProgress,
                calculateDaysRemaining(source.expires_at())
        );
    }

    private String normalizeStatus(String status, String expiresAt) {
        if (!"active".equalsIgnoreCase(status) || expiresAt == null || expiresAt.isBlank()) {
            return status;
        }
        Instant expires = Instant.parse(expiresAt);
        return expires.isAfter(Instant.now()) ? status : "expired";
    }

    private boolean isWindowExpired(String windowStart, int days) {
        if (windowStart == null || windowStart.isBlank()) {
            return false;
        }
        Instant resetAt = Instant.parse(windowStart).plus(days, ChronoUnit.DAYS);
        return !resetAt.isAfter(Instant.now());
    }

    private Double calculateProgress(double used, Double limit) {
        if (limit == null || limit <= 0) {
            return null;
        }
        return Math.min(100.0, (used / limit) * 100.0);
    }

    private Integer calculateDaysRemaining(String expiresAt) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return null;
        }
        Instant expires = Instant.parse(expiresAt);
        Instant now = Instant.now();
        if (!expires.isAfter(now)) {
            return 0;
        }
        return (int) Math.max(0, java.time.Duration.between(now, expires).toHours() / 24);
    }
}
