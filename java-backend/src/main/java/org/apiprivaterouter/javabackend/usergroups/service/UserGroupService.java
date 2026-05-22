package org.apiprivaterouter.javabackend.usergroups.service;

import org.apiprivaterouter.javabackend.admin.group.model.AdminGroupResponse;
import org.apiprivaterouter.javabackend.admin.group.service.AdminGroupService;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.usercenter.repository.UserSubscriptionRepository;
import org.apiprivaterouter.javabackend.usergroups.model.UserAvailableGroupResponse;
import org.apiprivaterouter.javabackend.usergroups.repository.UserGroupRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserGroupService {

    private static final String SUBSCRIPTION_TYPE_SUBSCRIPTION = "subscription";

    private final AdminGroupService adminGroupService;
    private final UserGroupRepository repository;
    private final UserSubscriptionRepository userSubscriptionRepository;

    public UserGroupService(
            AdminGroupService adminGroupService,
            UserGroupRepository repository,
            UserSubscriptionRepository userSubscriptionRepository
    ) {
        this.adminGroupService = adminGroupService;
        this.repository = repository;
        this.userSubscriptionRepository = userSubscriptionRepository;
    }

    public List<UserAvailableGroupResponse> getAvailableGroups(CurrentUser currentUser) {
        Set<Long> allowedGroupIds = repository.findAllowedGroupIds(currentUser.userId()).stream().collect(Collectors.toSet());
        Set<Long> subscribedGroupIds = userSubscriptionRepository.listActiveByUser(currentUser.userId()).stream()
                .map(subscription -> subscription.group_id())
                .collect(Collectors.toSet());
        return adminGroupService.listAllGroups(null).stream()
                .filter(group -> canBind(group, allowedGroupIds, subscribedGroupIds))
                .map(this::toResponse)
                .toList();
    }

    public Map<Long, Double> getUserGroupRates(CurrentUser currentUser) {
        return repository.findRateMultipliers(currentUser.userId());
    }

    private boolean canBind(AdminGroupResponse group, Set<Long> allowedGroupIds, Set<Long> subscribedGroupIds) {
        if (SUBSCRIPTION_TYPE_SUBSCRIPTION.equalsIgnoreCase(group.subscription_type())) {
            return subscribedGroupIds.contains(group.id());
        }
        if (!group.is_exclusive()) {
            return true;
        }
        return allowedGroupIds.contains(group.id());
    }

    private UserAvailableGroupResponse toResponse(AdminGroupResponse group) {
        return new UserAvailableGroupResponse(
                group.id(),
                group.name(),
                group.description(),
                group.platform(),
                group.rate_multiplier(),
                group.rpm_limit(),
                group.is_exclusive(),
                group.status(),
                group.subscription_type(),
                group.daily_limit_usd(),
                group.weekly_limit_usd(),
                group.monthly_limit_usd(),
                group.allow_image_generation(),
                group.image_rate_independent(),
                group.image_rate_multiplier(),
                group.image_price_1k(),
                group.image_price_2k(),
                group.image_price_4k(),
                group.claude_code_only(),
                group.fallback_group_id(),
                group.fallback_group_id_on_invalid_request(),
                group.allow_messages_dispatch(),
                group.default_mapped_model(),
                toDispatchConfig(group.messages_dispatch_model_config()),
                group.require_oauth_only(),
                group.require_privacy_set(),
                group.supported_model_scopes(),
                group.created_at(),
                group.updated_at()
        );
    }

    private UserAvailableGroupResponse.MessagesDispatchModelConfig toDispatchConfig(
            AdminGroupResponse.MessagesDispatchModelConfig source
    ) {
        if (source == null) {
            return null;
        }
        return new UserAvailableGroupResponse.MessagesDispatchModelConfig(
                source.opus_mapped_model(),
                source.sonnet_mapped_model(),
                source.haiku_mapped_model(),
                source.exact_model_mappings()
        );
    }
}
