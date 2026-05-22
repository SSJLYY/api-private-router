package org.apiprivaterouter.javabackend.admin.riskcontrol.service;

import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationUnbanUserResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.repository.ContentModerationRepository;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class ContentModerationBanService {

    private static final String USER_STATUS_ACTIVE = "active";
    private static final String USER_STATUS_DISABLED = "disabled";

    private final ContentModerationRepository moderationRepository;

    public ContentModerationBanService(ContentModerationRepository moderationRepository) {
        this.moderationRepository = moderationRepository;
    }

    public ContentModerationUnbanUserResponse unbanUser(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Invalid user_id");
        }
        if (moderationRepository.findUserStatus(userId).isEmpty()) {
            throw new HttpStatusException(404, "user not found");
        }
        moderationRepository.activateUser(userId);
        return new ContentModerationUnbanUserResponse(userId, USER_STATUS_ACTIVE);
    }

    public ContentModerationBanDecision applyAutoBanIfNeeded(long userId, String currentStatus, ContentModerationLoadedConfig config) {
        if (userId <= 0 || config == null) {
            return new ContentModerationBanDecision(0, false, false, currentStatus == null ? "" : currentStatus);
        }

        int violationCount = 1;
        if (config.response().violation_window_hours() > 0) {
            Instant since = Instant.now().minus(config.response().violation_window_hours(), ChronoUnit.HOURS);
            violationCount = moderationRepository.countFlaggedByUserSince(userId, since) + 1;
        }

        boolean autoBanned = false;
        boolean banApplied = false;
        String nextStatus = currentStatus == null ? "" : currentStatus;
        if (config.response().auto_ban_enabled()
                && config.response().ban_threshold() > 0
                && violationCount >= config.response().ban_threshold()) {
            autoBanned = true;
            if (!USER_STATUS_DISABLED.equalsIgnoreCase(nextStatus)) {
                moderationRepository.disableUser(userId);
                nextStatus = USER_STATUS_DISABLED;
                banApplied = true;
            } else {
                nextStatus = USER_STATUS_DISABLED;
            }
        }

        return new ContentModerationBanDecision(violationCount, autoBanned, banApplied, nextStatus);
    }
}
