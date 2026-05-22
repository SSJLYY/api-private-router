package org.apiprivaterouter.javabackend.admin.apikey.service;

import org.apiprivaterouter.javabackend.admin.apikey.model.AdminUpdateApiKeyGroupRequest;
import org.apiprivaterouter.javabackend.admin.apikey.model.AdminUpdateApiKeyGroupResponse;
import org.apiprivaterouter.javabackend.admin.apikey.repository.AdminApiKeyRepository;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.userkeys.model.UserApiKeyResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminApiKeyService {

    private final AdminApiKeyRepository repository;

    public AdminApiKeyService(AdminApiKeyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AdminUpdateApiKeyGroupResponse updateApiKey(long id, AdminUpdateApiKeyGroupRequest request) {
        AdminApiKeyRepository.ApiKeyAdminRow current = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("api key not found"));

        boolean resetUsage = Boolean.TRUE.equals(request.resetRateLimitUsage());
        Long requestedGroupId = request.groupId();
        Long normalizedGroupId = requestedGroupId;
        boolean autoGranted = false;
        Long grantedGroupId = null;
        String grantedGroupName = "";

        if (requestedGroupId != null) {
            if (requestedGroupId < 0) {
                throw new StructuredApiErrorException(400, "INVALID_GROUP_ID", "group_id must be >= 0");
            }
            if (requestedGroupId == 0) {
                normalizedGroupId = null;
            } else {
                AdminApiKeyRepository.GroupRow group = repository.findGroupById(requestedGroupId)
                        .orElseThrow(() -> new IllegalArgumentException("group not found"));
                if (!"active".equalsIgnoreCase(group.status())) {
                    throw new StructuredApiErrorException(400, "GROUP_NOT_ACTIVE", "target group is not active");
                }
                if (group.isSubscriptionGroup() && !repository.hasActiveSubscription(current.userId(), group.id())) {
                    throw new StructuredApiErrorException(400, "SUBSCRIPTION_REQUIRED", "active subscription required for this group");
                }
                if (group.isExclusive() && !group.isSubscriptionGroup() && !repository.hasAllowedGroup(current.userId(), group.id())) {
                    repository.addAllowedGroup(current.userId(), group.id());
                    autoGranted = true;
                    grantedGroupId = group.id();
                    grantedGroupName = group.name();
                }
                normalizedGroupId = group.id();
            }
            repository.updateGroupId(id, normalizedGroupId);
        }

        if (resetUsage) {
            repository.resetRateLimitUsage(id);
        }

        UserApiKeyResponse updated = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("api key not found"))
                .response();

        return new AdminUpdateApiKeyGroupResponse(
                updated,
                autoGranted,
                grantedGroupId,
                grantedGroupName == null || grantedGroupName.isBlank() ? null : grantedGroupName
        );
    }
}
