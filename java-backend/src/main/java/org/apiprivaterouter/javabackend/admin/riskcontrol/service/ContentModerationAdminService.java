package org.apiprivaterouter.javabackend.admin.riskcontrol.service;

import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ClearFlaggedHashesResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationConfigResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationLogResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationRuntimeStatusResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationUnbanUserResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.DeleteFlaggedHashRequest;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.DeleteFlaggedHashResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.TestContentModerationApiKeysRequest;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.TestContentModerationApiKeysResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.model.UpdateContentModerationConfigRequest;
import org.apiprivaterouter.javabackend.admin.riskcontrol.repository.ContentModerationHashRepository;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.stereotype.Service;

@Service
public class ContentModerationAdminService {

    private final ContentModerationConfigService configService;
    private final ContentModerationRuntimeService runtimeService;
    private final org.apiprivaterouter.javabackend.riskcontrol.runtime.ContentModerationRuntimeService gatewayRuntimeService;
    private final ContentModerationAuditService auditService;
    private final ContentModerationLogService logService;
    private final ContentModerationBanService banService;
    private final ContentModerationHashRepository hashRepository;

    public ContentModerationAdminService(
            ContentModerationConfigService configService,
            ContentModerationRuntimeService runtimeService,
            org.apiprivaterouter.javabackend.riskcontrol.runtime.ContentModerationRuntimeService gatewayRuntimeService,
            ContentModerationAuditService auditService,
            ContentModerationLogService logService,
            ContentModerationBanService banService,
            ContentModerationHashRepository hashRepository
    ) {
        this.configService = configService;
        this.runtimeService = runtimeService;
        this.gatewayRuntimeService = gatewayRuntimeService;
        this.auditService = auditService;
        this.logService = logService;
        this.banService = banService;
        this.hashRepository = hashRepository;
    }

    public ContentModerationConfigResponse getConfig() {
        return gatewayRuntimeService.toConfigResponse(gatewayRuntimeService.loadConfigSnapshot());
    }

    public ContentModerationConfigResponse updateConfig(UpdateContentModerationConfigRequest request) {
        return configService.updateConfig(request);
    }

    public ContentModerationRuntimeStatusResponse getStatus() {
        org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ContentModerationRuntimeStatus status =
                gatewayRuntimeService.getRuntimeStatus();
        return new ContentModerationRuntimeStatusResponse(
                status.enabled(),
                status.riskControlEnabled(),
                status.mode(),
                status.workerCount(),
                status.maxWorkers(),
                status.activeWorkers(),
                status.idleWorkers(),
                status.queueSize(),
                status.queueLength(),
                status.queueUsagePercent(),
                status.enqueued(),
                status.dropped(),
                status.processed(),
                status.errors(),
                status.apiKeyStatuses(),
                status.flaggedHashCount(),
                status.lastCleanupAt() == null ? null : status.lastCleanupAt().toString(),
                status.lastCleanupDeletedHit(),
                status.lastCleanupDeletedNonHit()
        );
    }

    public TestContentModerationApiKeysResponse testApiKeys(TestContentModerationApiKeysRequest request) {
        return auditService.testApiKeys(configService.loadConfig(), request);
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
        return logService.listLogs(page, pageSize, result, groupId, endpoint, search, from, to);
    }

    public ContentModerationUnbanUserResponse unbanUser(long userId) {
        return banService.unbanUser(userId);
    }

    public DeleteFlaggedHashResponse deleteFlaggedHash(DeleteFlaggedHashRequest request) {
        String inputHash = ContentModerationSupport.normalizeInputHash(request == null ? null : request.input_hash());
        if (inputHash == null) {
            throw new IllegalArgumentException("input_hash is required");
        }
        return new DeleteFlaggedHashResponse(inputHash, hashRepository.delete(inputHash));
    }

    public ClearFlaggedHashesResponse clearFlaggedHashes() {
        return new ClearFlaggedHashesResponse(hashRepository.clearAll());
    }
}
