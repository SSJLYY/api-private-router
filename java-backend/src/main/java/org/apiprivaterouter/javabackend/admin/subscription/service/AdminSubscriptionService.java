package org.apiprivaterouter.javabackend.admin.subscription.service;

import org.apiprivaterouter.javabackend.admin.subscription.model.AdminSubscriptionResponse;
import org.apiprivaterouter.javabackend.admin.subscription.model.AssignSubscriptionRequest;
import org.apiprivaterouter.javabackend.admin.subscription.model.BulkAssignSubscriptionRequest;
import org.apiprivaterouter.javabackend.admin.subscription.model.BulkAssignSubscriptionResponse;
import org.apiprivaterouter.javabackend.admin.subscription.model.ExtendSubscriptionRequest;
import org.apiprivaterouter.javabackend.admin.subscription.model.ResetSubscriptionQuotaRequest;
import org.apiprivaterouter.javabackend.admin.subscription.model.SubscriptionProgressResponse;
import org.apiprivaterouter.javabackend.admin.subscription.repository.AdminSubscriptionRepository;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class AdminSubscriptionService {

    private static final int DEFAULT_VALIDITY_DAYS = 30;
    private static final int MAX_VALIDITY_DAYS = 36500;
    private static final OffsetDateTime MAX_EXPIRES_AT =
            OffsetDateTime.parse("2099-12-31T23:59:59Z");
    private static final List<String> ALLOWED_STATUSES = List.of("active", "expired", "revoked", "suspended");

    private final AdminSubscriptionRepository repository;

    public AdminSubscriptionService(AdminSubscriptionRepository repository) {
        this.repository = repository;
    }

    public PageResponse<AdminSubscriptionResponse> listSubscriptions(
            int page,
            int pageSize,
            Long userId,
            Long groupId,
            String status,
            String platform,
            String sortBy,
            String sortOrder
    ) {
        return repository.listSubscriptions(
                normalizePage(page),
                normalizePageSize(pageSize),
                userId,
                groupId,
                normalizeStatus(status),
                normalizePlatform(platform),
                sortBy,
                sortOrder
        );
    }

    public AdminSubscriptionResponse getSubscription(long id) {
        return repository.getSubscription(id)
                .orElseThrow(() -> new IllegalArgumentException("subscription not found"));
    }

    public SubscriptionProgressResponse getProgress(long id) {
        return toProgress(getSubscription(id));
    }

    public SubscriptionProgressResponse toProgress(AdminSubscriptionResponse subscription) {
        Instant now = Instant.now();
        return new SubscriptionProgressResponse(
                subscription.id(),
                buildWindowProgress(subscription.daily_usage_usd(), subscription.group().daily_limit_usd(), subscription.daily_window_start(), 1, now),
                buildWindowProgress(subscription.weekly_usage_usd(), subscription.group().weekly_limit_usd(), subscription.weekly_window_start(), 7, now),
                buildWindowProgress(subscription.monthly_usage_usd(), subscription.group().monthly_limit_usd(), subscription.monthly_window_start(), 30, now),
                subscription.expires_at(),
                calculateDaysRemaining(subscription.expires_at(), now)
        );
    }

    public AdminSubscriptionResponse assignSubscription(AssignSubscriptionRequest request, long adminId) {
        validateAssignableTargets(request.user_id(), request.group_id());
        AssignResult result = assignOrReuse(request.user_id(), request.group_id(), request.validity_days(), request.notes(), adminId);
        return result.subscription();
    }

    public BulkAssignSubscriptionResponse bulkAssignSubscriptions(BulkAssignSubscriptionRequest request, long adminId) {
        if (!repository.subscriptionGroupExists(request.group_id())) {
            throw new IllegalArgumentException("group must be subscription type");
        }
        List<AdminSubscriptionResponse> subscriptions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, String> statuses = new LinkedHashMap<>();
        int successCount = 0;
        int createdCount = 0;
        int reusedCount = 0;
        int failedCount = 0;

        for (Long userId : new LinkedHashSet<>(request.user_ids())) {
            if (userId == null || userId <= 0) {
                continue;
            }
            try {
                if (!repository.userExists(userId)) {
                    throw new IllegalArgumentException("user not found");
                }
                AssignResult result = assignOrReuse(userId, request.group_id(), request.validity_days(), request.notes(), adminId);
                subscriptions.add(result.subscription());
                successCount++;
                if (result.reused()) {
                    reusedCount++;
                    statuses.put(String.valueOf(userId), "reused");
                } else {
                    createdCount++;
                    statuses.put(String.valueOf(userId), "created");
                }
            } catch (Exception ex) {
                failedCount++;
                statuses.put(String.valueOf(userId), "failed");
                errors.add("user " + userId + ": " + ex.getMessage());
            }
        }

        return new BulkAssignSubscriptionResponse(
                successCount,
                createdCount,
                reusedCount,
                failedCount,
                subscriptions,
                errors,
                statuses
        );
    }

    public AdminSubscriptionResponse extendSubscription(long id, ExtendSubscriptionRequest request) {
        AdminSubscriptionRepository.SubscriptionSnapshot snapshot = repository.getSubscriptionSnapshot(id)
                .orElseThrow(() -> new IllegalArgumentException("subscription not found"));
        int days = clampAdjustDays(request.days());
        OffsetDateTime now = OffsetDateTime.now();
        boolean expired = !snapshot.expiresAt().isAfter(now);
        if (expired && days < 0) {
            throw new IllegalArgumentException("cannot shorten an expired subscription");
        }

        OffsetDateTime newExpiresAt = expired
                ? now.plusDays(days)
                : snapshot.expiresAt().plusDays(days);
        if (newExpiresAt.isAfter(MAX_EXPIRES_AT)) {
            newExpiresAt = MAX_EXPIRES_AT;
        }
        if (!newExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("adjustment would result in expired subscription (remaining days must be > 0)");
        }

        repository.updateExpiresAt(id, newExpiresAt);
        if ("expired".equalsIgnoreCase(snapshot.status())) {
            repository.updateStatus(id, "active");
        }
        return getSubscription(id);
    }

    public AdminSubscriptionResponse resetQuota(long id, ResetSubscriptionQuotaRequest request) {
        if (!request.daily() && !request.weekly() && !request.monthly()) {
            throw new IllegalArgumentException("at least one of daily, weekly, or monthly must be true");
        }
        repository.getSubscriptionSnapshot(id)
                .orElseThrow(() -> new IllegalArgumentException("subscription not found"));
        OffsetDateTime windowStart = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay()
                .atOffset(OffsetDateTime.now().getOffset());
        repository.resetQuota(id, request.daily(), request.weekly(), request.monthly(), windowStart);
        return getSubscription(id);
    }

    public Map<String, String> revokeSubscription(long id) {
        repository.getSubscriptionSnapshot(id)
                .orElseThrow(() -> new IllegalArgumentException("subscription not found"));
        repository.revokeSubscription(id);
        return Map.of("message", "Subscription revoked successfully");
    }

    public PageResponse<AdminSubscriptionResponse> listGroupSubscriptions(long groupId, int page, int pageSize) {
        return listSubscriptions(page, pageSize, null, groupId, null, null, "created_at", "desc");
    }

    public PageResponse<AdminSubscriptionResponse> listUserSubscriptions(long userId, int page, int pageSize) {
        return listSubscriptions(page, pageSize, userId, null, null, null, "created_at", "desc");
    }

    private AssignResult assignOrReuse(long userId, long groupId, Integer validityDays, String notes, long adminId) {
        int normalizedDays = normalizeValidityDays(validityDays);
        String normalizedNotes = normalizeNotes(notes);
        AdminSubscriptionRepository.SubscriptionSnapshot existing = repository.findExistingByUserAndGroup(userId, groupId)
                .orElse(null);
        if (existing != null) {
            if (hasAssignSemanticConflict(existing, normalizedDays, normalizedNotes)) {
                throw new IllegalArgumentException("subscription exists but request conflicts with existing assignment semantics");
            }
            return new AssignResult(getSubscription(existing.id()), true);
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = clampExpiresAt(now.plusDays(normalizedDays));
        long id = repository.createSubscription(
                userId,
                groupId,
                now,
                expiresAt,
                "active",
                adminId > 0 ? adminId : null,
                now,
                normalizedNotes
        );
        return new AssignResult(getSubscription(id), false);
    }

    private boolean hasAssignSemanticConflict(AdminSubscriptionRepository.SubscriptionSnapshot existing, int validityDays, String notes) {
        OffsetDateTime expectedExpiresAt = clampExpiresAt(existing.startsAt().plusDays(validityDays));
        if (!expectedExpiresAt.isEqual(existing.expiresAt())) {
            return true;
        }
        return !normalizeNotes(existing.notes()).equals(notes);
    }

    private void validateAssignableTargets(long userId, long groupId) {
        if (!repository.userExists(userId)) {
            throw new IllegalArgumentException("user not found");
        }
        if (!repository.subscriptionGroupExists(groupId)) {
            throw new IllegalArgumentException("group must be subscription type");
        }
    }

    private SubscriptionProgressResponse.WindowProgress buildWindowProgress(
            double used,
            Double limit,
            String windowStart,
            int days,
            Instant now
    ) {
        if (limit == null || windowStart == null || windowStart.isBlank()) {
            return null;
        }
        Instant resetAt = Instant.parse(windowStart).plus(days, ChronoUnit.DAYS);
        long resetInSeconds = Math.max(0L, Duration.between(now, resetAt).getSeconds());
        double percentage = limit <= 0 ? 0 : Math.min(100.0, (used / limit) * 100.0);
        return new SubscriptionProgressResponse.WindowProgress(used, limit, percentage, resetInSeconds);
    }

    private Integer calculateDaysRemaining(String expiresAt, Instant now) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return null;
        }
        Instant expires = Instant.parse(expiresAt);
        if (!expires.isAfter(now)) {
            return 0;
        }
        return (int) Math.max(0, Duration.between(now, expires).toHours() / 24);
    }

    private int normalizePage(int page) {
        return Math.max(page, 1);
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 200);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toLowerCase();
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status is invalid");
        }
        return normalized;
    }

    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return null;
        }
        return platform.trim().toLowerCase();
    }

    private int normalizeValidityDays(Integer validityDays) {
        if (validityDays == null || validityDays <= 0) {
            return DEFAULT_VALIDITY_DAYS;
        }
        return Math.min(validityDays, MAX_VALIDITY_DAYS);
    }

    private int clampAdjustDays(int days) {
        if (days > MAX_VALIDITY_DAYS) {
            return MAX_VALIDITY_DAYS;
        }
        if (days < -MAX_VALIDITY_DAYS) {
            return -MAX_VALIDITY_DAYS;
        }
        return days;
    }

    private OffsetDateTime clampExpiresAt(OffsetDateTime expiresAt) {
        return expiresAt.isAfter(MAX_EXPIRES_AT) ? MAX_EXPIRES_AT : expiresAt;
    }

    private String normalizeNotes(String notes) {
        return notes == null ? "" : notes.trim();
    }

    private record AssignResult(AdminSubscriptionResponse subscription, boolean reused) {
    }
}
