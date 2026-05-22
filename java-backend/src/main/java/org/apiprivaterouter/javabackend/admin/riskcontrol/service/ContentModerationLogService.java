package org.apiprivaterouter.javabackend.admin.riskcontrol.service;

import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationLogResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.repository.ContentModerationRepository;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class ContentModerationLogService {

    private final ContentModerationRepository moderationRepository;
    private final ContentModerationConfigService configService;
    private final ContentModerationRuntimeService runtimeService;
    private final ContentModerationBanService banService;

    public ContentModerationLogService(
            ContentModerationRepository moderationRepository,
            ContentModerationConfigService configService,
            ContentModerationRuntimeService runtimeService,
            ContentModerationBanService banService
    ) {
        this.moderationRepository = moderationRepository;
        this.configService = configService;
        this.runtimeService = runtimeService;
        this.banService = banService;
    }

    public PageResponse<ContentModerationLogResponse> listLogs(
            int page,
            int pageSize,
            String result,
            Long groupId,
            String endpoint,
            String search,
            String from,
            String to
    ) {
        if (groupId != null && groupId <= 0) {
            throw new IllegalArgumentException("Invalid group_id");
        }
        return moderationRepository.listLogs(
                page,
                pageSize,
                ContentModerationSupport.trimToNull(result),
                groupId,
                ContentModerationSupport.trimToNull(endpoint),
                ContentModerationSupport.trimToNull(search),
                ContentModerationSupport.normalizeLogTime(from, false, "from"),
                ContentModerationSupport.normalizeLogTime(to, true, "to")
        );
    }

    public ContentModerationLogResponse recordLog(
            ContentModerationLogCommand command,
            ContentModerationLoadedConfig config,
            String currentUserStatus
    ) {
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        int violationCount = 0;
        boolean autoBanned = false;
        String userStatus = currentUserStatus == null ? "" : currentUserStatus;

        if (command.flagged() && command.userId() != null && command.userId() > 0 && config != null) {
            ContentModerationBanDecision decision = banService.applyAutoBanIfNeeded(command.userId(), userStatus, config);
            violationCount = decision.violationCount();
            autoBanned = decision.autoBanned();
            userStatus = decision.status();
        }

        return moderationRepository.createLog(command, violationCount, autoBanned, userStatus);
    }

    @Scheduled(
            fixedDelay = ContentModerationSupport.CLEANUP_INTERVAL_MS,
            initialDelay = ContentModerationSupport.CLEANUP_INITIAL_DELAY_MS
    )
    public void cleanupExpiredLogs() {
        ContentModerationLoadedConfig config = configService.loadConfig();
        Instant now = Instant.now();
        Instant hitBefore = now.minusSeconds((long) config.response().hit_retention_days() * 86400L);
        Instant nonHitBefore = now.minusSeconds((long) config.response().non_hit_retention_days() * 86400L);
        ContentModerationRepository.CleanupResult result = moderationRepository.cleanupExpiredLogs(hitBefore, nonHitBefore);
        runtimeService.updateCleanupSnapshot(result.finishedAt(), result.deletedHit(), result.deletedNonHit());
    }
}
