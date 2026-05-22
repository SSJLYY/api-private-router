package org.apiprivaterouter.javabackend.userredeem.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.userredeem.model.UserRedeemHistoryItemResponse;
import org.apiprivaterouter.javabackend.userredeem.model.UserRedeemRequest;
import org.apiprivaterouter.javabackend.userredeem.model.UserRedeemResultResponse;
import org.apiprivaterouter.javabackend.userredeem.repository.UserRedeemRepository;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class UserRedeemService {

    private static final int DEFAULT_HISTORY_LIMIT = 25;
    private static final int DEFAULT_SUBSCRIPTION_VALIDITY_DAYS = 30;
    private static final int MAX_VALIDITY_DAYS = 36500;
    private static final OffsetDateTime MAX_EXPIRES_AT = OffsetDateTime.parse("2099-12-31T23:59:59Z");
    private static final String SUBSCRIPTION_STATUS_ACTIVE = "active";
    private static final String SUBSCRIPTION_STATUS_EXPIRED = "expired";

    private final UserRedeemRepository userRedeemRepository;

    public UserRedeemService(UserRedeemRepository userRedeemRepository) {
        this.userRedeemRepository = userRedeemRepository;
    }

    public List<UserRedeemHistoryItemResponse> getHistory(CurrentUser currentUser) {
        return userRedeemRepository.listHistory(currentUser.userId(), DEFAULT_HISTORY_LIMIT);
    }

    @Transactional
    public UserRedeemResultResponse redeem(CurrentUser currentUser, UserRedeemRequest request) {
        String code = normalizeCode(request.code());
        UserRedeemRepository.RedeemCodeRecord redeemCode = userRedeemRepository.findByCodeForUpdate(code)
                .orElseThrow(() -> new IllegalArgumentException("redeem code not found"));

        if (!UserRedeemRepository.STATUS_UNUSED.equalsIgnoreCase(redeemCode.status())) {
            throw new IllegalArgumentException("redeem code already used");
        }

        UserRedeemRepository.UserAccountSnapshot user = userRedeemRepository.findUserById(currentUser.userId())
                .orElseThrow(() -> new IllegalArgumentException("user not found"));

        validateRedeemCode(redeemCode);

        if (userRedeemRepository.markUsed(redeemCode.id(), user.id()) != 1) {
            throw new IllegalArgumentException("redeem code already used");
        }

        return switch (redeemCode.type()) {
            case UserRedeemRepository.TYPE_BALANCE -> redeemBalance(user, redeemCode);
            case UserRedeemRepository.TYPE_CONCURRENCY -> redeemConcurrency(user, redeemCode);
            case UserRedeemRepository.TYPE_SUBSCRIPTION -> redeemSubscription(user, redeemCode);
            default -> throw new IllegalArgumentException("unsupported redeem type: " + redeemCode.type());
        };
    }

    private UserRedeemResultResponse redeemBalance(
            UserRedeemRepository.UserAccountSnapshot user,
            UserRedeemRepository.RedeemCodeRecord redeemCode
    ) {
        double appliedAmount = redeemCode.value();
        if (appliedAmount < 0 && user.balance() + appliedAmount < 0) {
            appliedAmount = -user.balance();
        }
        userRedeemRepository.addBalance(user.id(), appliedAmount);
        double newBalance = user.balance() + appliedAmount;
        return new UserRedeemResultResponse(
                "Redeem successful",
                redeemCode.type(),
                redeemCode.value(),
                newBalance,
                null,
                null,
                null
        );
    }

    private UserRedeemResultResponse redeemConcurrency(
            UserRedeemRepository.UserAccountSnapshot user,
            UserRedeemRepository.RedeemCodeRecord redeemCode
    ) {
        int appliedDelta = (int) redeemCode.value();
        if (appliedDelta < 0 && user.concurrency() + appliedDelta < 0) {
            appliedDelta = -user.concurrency();
        }
        userRedeemRepository.addConcurrency(user.id(), appliedDelta);
        int newConcurrency = user.concurrency() + appliedDelta;
        return new UserRedeemResultResponse(
                "Redeem successful",
                redeemCode.type(),
                redeemCode.value(),
                null,
                newConcurrency,
                null,
                null
        );
    }

    private UserRedeemResultResponse redeemSubscription(
            UserRedeemRepository.UserAccountSnapshot user,
            UserRedeemRepository.RedeemCodeRecord redeemCode
    ) {
        if (redeemCode.groupId() == null) {
            throw new IllegalArgumentException("invalid subscription redeem code: missing group_id");
        }
        if (!"subscription".equalsIgnoreCase(redeemCode.subscriptionType())) {
            throw new IllegalArgumentException("group must be subscription type");
        }

        int validityDays = redeemCode.validityDays();
        if (validityDays < 0) {
            reduceOrCancelSubscription(user.id(), redeemCode.groupId(), -validityDays, redeemCode.code());
            return new UserRedeemResultResponse(
                    "Redeem successful",
                    redeemCode.type(),
                    redeemCode.value(),
                    null,
                    null,
                    redeemCode.groupName(),
                    validityDays
            );
        }

        int normalizedValidityDays = normalizeValidityDays(validityDays);
        assignOrExtendSubscription(user.id(), redeemCode.groupId(), normalizedValidityDays, redeemCode.code());
        return new UserRedeemResultResponse(
                "Redeem successful",
                redeemCode.type(),
                redeemCode.value(),
                null,
                null,
                redeemCode.groupName(),
                normalizedValidityDays
        );
    }

    private void assignOrExtendSubscription(long userId, long groupId, int validityDays, String code) {
        OffsetDateTime now = OffsetDateTime.now();
        String note = "Redeemed via code " + code;
        UserRedeemRepository.SubscriptionRecord existing = userRedeemRepository.findLatestSubscriptionForUpdate(userId, groupId)
                .orElse(null);
        if (existing == null) {
            OffsetDateTime expiresAt = clampExpiresAt(now.plusDays(validityDays));
            userRedeemRepository.createSubscription(
                    userId,
                    groupId,
                    now,
                    expiresAt,
                    SUBSCRIPTION_STATUS_ACTIVE,
                    note
            );
            return;
        }

        OffsetDateTime newExpiresAt = existing.expiresAt() != null && existing.expiresAt().isAfter(now)
                ? existing.expiresAt().plusDays(validityDays)
                : now.plusDays(validityDays);
        userRedeemRepository.updateSubscriptionExpiry(existing.id(), clampExpiresAt(newExpiresAt));
        if (!SUBSCRIPTION_STATUS_ACTIVE.equalsIgnoreCase(existing.status())) {
            userRedeemRepository.updateSubscriptionStatus(existing.id(), SUBSCRIPTION_STATUS_ACTIVE);
        }
        userRedeemRepository.updateSubscriptionNotes(existing.id(), appendNotes(existing.notes(), note));
    }

    private void reduceOrCancelSubscription(long userId, long groupId, int reduceDays, String code) {
        UserRedeemRepository.SubscriptionRecord existing = userRedeemRepository.findLatestSubscriptionForUpdate(userId, groupId)
                .orElseThrow(() -> new IllegalArgumentException("subscription not found"));

        OffsetDateTime now = OffsetDateTime.now();
        long remainingDays = existing.expiresAt() == null ? 0 : java.time.Duration.between(now, existing.expiresAt()).toHours() / 24;
        if (remainingDays < 0) {
            remainingDays = 0;
        }

        String note = "Reduced via code " + code + " by " + reduceDays + " days";
        if (remainingDays <= reduceDays) {
            userRedeemRepository.updateSubscriptionStatus(existing.id(), SUBSCRIPTION_STATUS_EXPIRED);
            userRedeemRepository.updateSubscriptionExpiry(existing.id(), now);
        } else {
            userRedeemRepository.updateSubscriptionExpiry(existing.id(), existing.expiresAt().minusDays(reduceDays));
        }
        userRedeemRepository.updateSubscriptionNotes(existing.id(), appendNotes(existing.notes(), note));
    }

    private void validateRedeemCode(UserRedeemRepository.RedeemCodeRecord redeemCode) {
        if (UserRedeemRepository.TYPE_SUBSCRIPTION.equalsIgnoreCase(redeemCode.type()) && redeemCode.groupId() == null) {
            throw new IllegalArgumentException("invalid subscription redeem code: missing group_id");
        }
    }

    private int normalizeValidityDays(int validityDays) {
        if (validityDays <= 0) {
            return DEFAULT_SUBSCRIPTION_VALIDITY_DAYS;
        }
        return Math.min(validityDays, MAX_VALIDITY_DAYS);
    }

    private OffsetDateTime clampExpiresAt(OffsetDateTime expiresAt) {
        return expiresAt.isAfter(MAX_EXPIRES_AT) ? MAX_EXPIRES_AT : expiresAt;
    }

    private String normalizeCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("code is required");
        }
        return code.trim();
    }

    private String appendNotes(String existing, String appended) {
        if (appended == null || appended.isBlank()) {
            return existing == null ? "" : existing;
        }
        if (existing == null || existing.isBlank()) {
            return appended;
        }
        return existing + "\n" + appended;
    }
}
