package org.apiprivaterouter.javabackend.admin.group.service;

import org.apiprivaterouter.javabackend.admin.group.model.AdminGroupResponse;
import org.apiprivaterouter.javabackend.admin.group.model.BatchSetGroupRateMultipliersRequest;
import org.apiprivaterouter.javabackend.admin.group.model.BatchSetGroupRpmOverridesRequest;
import org.apiprivaterouter.javabackend.admin.group.model.CreateAdminGroupRequest;
import org.apiprivaterouter.javabackend.admin.group.model.GroupCapacitySummaryResponse;
import org.apiprivaterouter.javabackend.admin.group.model.GroupRateMultiplierEntryResponse;
import org.apiprivaterouter.javabackend.admin.group.model.GroupStatsResponse;
import org.apiprivaterouter.javabackend.admin.group.model.GroupUsageSummaryResponse;
import org.apiprivaterouter.javabackend.admin.group.model.UpdateAdminGroupRequest;
import org.apiprivaterouter.javabackend.admin.group.model.UpdateGroupSortOrderRequest;
import org.apiprivaterouter.javabackend.admin.group.repository.AdminGroupRepository;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class AdminGroupService {

    private static final String PLATFORM_ANTHROPIC = "anthropic";
    private static final String PLATFORM_OPENAI = "openai";
    private static final String PLATFORM_GEMINI = "gemini";
    private static final String PLATFORM_ANTIGRAVITY = "antigravity";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_INACTIVE = "inactive";
    private static final String SUBSCRIPTION_STANDARD = "standard";
    private static final String SUBSCRIPTION_SUBSCRIPTION = "subscription";
    private static final List<String> SUPPORTED_PLATFORMS = List.of(
            PLATFORM_ANTHROPIC, PLATFORM_OPENAI, PLATFORM_GEMINI, PLATFORM_ANTIGRAVITY
    );
    private static final List<String> SUPPORTED_STATUSES = List.of(STATUS_ACTIVE, STATUS_INACTIVE);
    private static final List<String> SUPPORTED_SUBSCRIPTION_TYPES = List.of(
            SUBSCRIPTION_STANDARD, SUBSCRIPTION_SUBSCRIPTION
    );
    private static final List<String> DEFAULT_MODEL_SCOPES = List.of("claude", "gemini_text", "gemini_image");
    private static final String ACCOUNT_TYPE_APIKEY = "apikey";

    private final AdminGroupRepository repository;

    public AdminGroupService(AdminGroupRepository repository) {
        this.repository = repository;
    }

    public PageResponse<AdminGroupResponse> listGroups(
            int page,
            int pageSize,
            String platform,
            String status,
            String search,
            Boolean isExclusive,
            String sortBy,
            String sortOrder
    ) {
        return repository.listGroups(
                normalizePage(page),
                normalizePageSize(pageSize),
                normalizePlatform(platform, true),
                normalizeStatus(status, true),
                normalizeSearch(search),
                isExclusive,
                sortBy,
                sortOrder
        );
    }

    public List<AdminGroupResponse> listAllGroups(String platform) {
        return repository.listAllActiveGroups(normalizePlatform(platform, true));
    }

    public AdminGroupResponse getGroup(long id) {
        return repository.getGroup(id).orElseThrow(() -> new IllegalArgumentException("group not found"));
    }

    public AdminGroupResponse createGroup(CreateAdminGroupRequest request) {
        String name = normalizeName(request.name());
        if (repository.groupNameExists(name, null)) {
            throw new IllegalArgumentException("group name already exists");
        }
        double rateMultiplier = normalizeCreateRateMultiplier(request.rate_multiplier());
        String platform = normalizePlatform(request.platform(), false);
        String subscriptionType = normalizeSubscriptionType(request.subscription_type(), false);
        Double dailyLimit = normalizeLimit(request.daily_limit_usd());
        Double weeklyLimit = normalizeLimit(request.weekly_limit_usd());
        Double monthlyLimit = normalizeLimit(request.monthly_limit_usd());
        Double imagePrice1k = normalizePrice(request.image_price_1k());
        Double imagePrice2k = normalizePrice(request.image_price_2k());
        Double imagePrice4k = normalizePrice(request.image_price_4k());
        double imageRateMultiplier = normalizeImageRateMultiplier(request.image_rate_multiplier());
        Long fallbackGroupId = normalizeFallbackId(request.fallback_group_id());
        Long fallbackInvalidGroupId = normalizeFallbackId(request.fallback_group_id_on_invalid_request());
        validateFallbackGroup(0, fallbackGroupId);
        validateInvalidFallbackGroup(0, platform, subscriptionType, fallbackInvalidGroupId);

        Map<String, List<Long>> modelRouting = normalizeModelRouting(request.model_routing());
        boolean modelRoutingEnabled = Boolean.TRUE.equals(request.model_routing_enabled());
        boolean mcpXmlInject = request.mcp_xml_inject() == null || request.mcp_xml_inject();
        boolean allowMessagesDispatch = Boolean.TRUE.equals(request.allow_messages_dispatch());
        boolean requireOauthOnly = Boolean.TRUE.equals(request.require_oauth_only());
        boolean requirePrivacySet = Boolean.TRUE.equals(request.require_privacy_set());
        int rpmLimit = normalizeRpmLimit(request.rpm_limit());
        List<String> supportedScopes = normalizeSupportedScopes(request.supported_model_scopes());
        AdminGroupResponse.MessagesDispatchModelConfig dispatchConfig = normalizeMessagesDispatchConfig(
                request.messages_dispatch_model_config()
        );
        String defaultMappedModel = normalizeOptionalText(request.default_mapped_model());
        dispatchConfig = sanitizeMessagesDispatchConfig(platform, dispatchConfig);
        if (!PLATFORM_OPENAI.equals(platform)) {
            allowMessagesDispatch = false;
            defaultMappedModel = "";
        }
        List<Long> copySourceGroupIds = normalizeCopySourceGroupIds(request.copy_accounts_from_group_ids(), null, platform);
        List<Long> accountIdsToCopy = collectCopyAccountIds(copySourceGroupIds, requireOauthOnly);
        int nextSortOrder = computeNextSortOrder();

        long groupId = repository.createGroup(new AdminGroupRepository.GroupWriteModel(
                name,
                normalizeDescription(request.description()),
                platform,
                rateMultiplier,
                Boolean.TRUE.equals(request.is_exclusive()) || SUBSCRIPTION_SUBSCRIPTION.equals(subscriptionType),
                STATUS_ACTIVE,
                subscriptionType,
                dailyLimit,
                weeklyLimit,
                monthlyLimit,
                Boolean.TRUE.equals(request.allow_image_generation()),
                Boolean.TRUE.equals(request.image_rate_independent()),
                imageRateMultiplier,
                imagePrice1k,
                imagePrice2k,
                imagePrice4k,
                Boolean.TRUE.equals(request.claude_code_only()),
                fallbackGroupId,
                fallbackInvalidGroupId,
                modelRouting,
                modelRoutingEnabled,
                mcpXmlInject,
                supportedScopes,
                allowMessagesDispatch,
                requireOauthOnly,
                requirePrivacySet,
                defaultMappedModel,
                dispatchConfig,
                rpmLimit,
                nextSortOrder
        ));
        if (!accountIdsToCopy.isEmpty()) {
            repository.bindAccountsToGroup(groupId, accountIdsToCopy);
        }
        return getGroup(groupId);
    }

    public AdminGroupResponse updateGroup(long id, UpdateAdminGroupRequest request) {
        AdminGroupResponse current = getGroup(id);
        String name = request.name() == null ? current.name() : normalizeName(request.name());
        if (repository.groupNameExists(name, id)) {
            throw new IllegalArgumentException("group name already exists");
        }
        String platform = request.platform() == null ? current.platform() : normalizePlatform(request.platform(), false);
        String status = request.status() == null ? current.status() : normalizeStatus(request.status(), false);
        String subscriptionType = request.subscription_type() == null
                ? current.subscription_type()
                : normalizeSubscriptionType(request.subscription_type(), false);
        double rateMultiplier = request.rate_multiplier() == null
                ? current.rate_multiplier()
                : normalizeUpdateRateMultiplier(request.rate_multiplier());

        Double dailyLimit = request.daily_limit_usd() == null ? current.daily_limit_usd() : normalizeLimit(request.daily_limit_usd());
        Double weeklyLimit = request.weekly_limit_usd() == null ? current.weekly_limit_usd() : normalizeLimit(request.weekly_limit_usd());
        Double monthlyLimit = request.monthly_limit_usd() == null ? current.monthly_limit_usd() : normalizeLimit(request.monthly_limit_usd());

        boolean allowImageGeneration = request.allow_image_generation() == null
                ? current.allow_image_generation() : request.allow_image_generation();
        boolean imageRateIndependent = request.image_rate_independent() == null
                ? current.image_rate_independent() : request.image_rate_independent();
        double imageRateMultiplier = request.image_rate_multiplier() == null
                ? current.image_rate_multiplier()
                : normalizeImageRateMultiplier(request.image_rate_multiplier());
        Double imagePrice1k = request.image_price_1k() == null ? current.image_price_1k() : normalizePrice(request.image_price_1k());
        Double imagePrice2k = request.image_price_2k() == null ? current.image_price_2k() : normalizePrice(request.image_price_2k());
        Double imagePrice4k = request.image_price_4k() == null ? current.image_price_4k() : normalizePrice(request.image_price_4k());

        boolean claudeCodeOnly = request.claude_code_only() == null ? current.claude_code_only() : request.claude_code_only();
        Long fallbackGroupId = request.fallback_group_id() == null
                ? current.fallback_group_id()
                : normalizeFallbackId(request.fallback_group_id());
        Long fallbackInvalidGroupId = request.fallback_group_id_on_invalid_request() == null
                ? current.fallback_group_id_on_invalid_request()
                : normalizeFallbackId(request.fallback_group_id_on_invalid_request());
        validateFallbackGroup(id, fallbackGroupId);
        validateInvalidFallbackGroup(id, platform, subscriptionType, fallbackInvalidGroupId);

        Map<String, List<Long>> modelRouting = request.model_routing() == null
                ? current.model_routing()
                : normalizeModelRouting(request.model_routing());
        boolean modelRoutingEnabled = request.model_routing_enabled() == null
                ? current.model_routing_enabled()
                : request.model_routing_enabled();
        boolean mcpXmlInject = request.mcp_xml_inject() == null ? current.mcp_xml_inject() : request.mcp_xml_inject();
        List<String> supportedScopes = request.supported_model_scopes() == null
                ? current.supported_model_scopes()
                : normalizeSupportedScopes(request.supported_model_scopes());
        boolean requireOauthOnly = request.require_oauth_only() == null
                ? current.require_oauth_only() : request.require_oauth_only();
        boolean requirePrivacySet = request.require_privacy_set() == null
                ? current.require_privacy_set() : request.require_privacy_set();
        boolean allowMessagesDispatch = request.allow_messages_dispatch() == null
                ? current.allow_messages_dispatch() : request.allow_messages_dispatch();
        String defaultMappedModel = request.default_mapped_model() == null
                ? current.default_mapped_model()
                : normalizeOptionalText(request.default_mapped_model());
        AdminGroupResponse.MessagesDispatchModelConfig dispatchConfig = request.messages_dispatch_model_config() == null
                ? current.messages_dispatch_model_config()
                : normalizeMessagesDispatchConfig(request.messages_dispatch_model_config());
        int rpmLimit = request.rpm_limit() == null ? current.rpm_limit() : normalizeRpmLimit(request.rpm_limit());

        dispatchConfig = sanitizeMessagesDispatchConfig(platform, dispatchConfig);
        if (!PLATFORM_OPENAI.equals(platform)) {
            allowMessagesDispatch = false;
            defaultMappedModel = "";
        }

        repository.updateGroup(id, new AdminGroupRepository.GroupWriteModel(
                name,
                request.description() == null ? current.description() : normalizeDescription(request.description()),
                platform,
                rateMultiplier,
                request.is_exclusive() == null
                        ? (SUBSCRIPTION_SUBSCRIPTION.equals(subscriptionType) ? true : current.is_exclusive())
                        : (SUBSCRIPTION_SUBSCRIPTION.equals(subscriptionType) || request.is_exclusive()),
                status,
                subscriptionType,
                dailyLimit,
                weeklyLimit,
                monthlyLimit,
                allowImageGeneration,
                imageRateIndependent,
                imageRateMultiplier,
                imagePrice1k,
                imagePrice2k,
                imagePrice4k,
                claudeCodeOnly,
                fallbackGroupId,
                fallbackInvalidGroupId,
                modelRouting,
                modelRoutingEnabled,
                mcpXmlInject,
                supportedScopes,
                allowMessagesDispatch,
                requireOauthOnly,
                requirePrivacySet,
                defaultMappedModel,
                dispatchConfig,
                rpmLimit,
                current.sort_order()
        ));

        if (request.copy_accounts_from_group_ids() != null) {
            List<Long> sourceGroupIds = normalizeCopySourceGroupIds(request.copy_accounts_from_group_ids(), id, platform);
            List<Long> accountIdsToCopy = collectCopyAccountIds(sourceGroupIds, requireOauthOnly);
            repository.clearGroupAccounts(id);
            if (!accountIdsToCopy.isEmpty()) {
                repository.bindAccountsToGroup(id, accountIdsToCopy);
            }
        }
        return getGroup(id);
    }

    public Map<String, String> deleteGroup(long id) {
        getGroup(id);
        repository.deleteGroup(id);
        return Map.of("message", "Group deleted successfully");
    }

    public GroupStatsResponse getGroupStats(long id) {
        return repository.getGroupStats(id);
    }

    public PageResponse<Map<String, Object>> getGroupApiKeys(long id, int page, int pageSize) {
        return repository.getGroupApiKeys(id, normalizePage(page), normalizePageSize(pageSize));
    }

    public List<GroupRateMultiplierEntryResponse> getGroupRateMultipliers(long id) {
        return repository.getGroupRateMultipliers(id);
    }

    public Map<String, String> clearGroupRateMultipliers(long id) {
        repository.clearGroupRateMultipliers(id);
        return Map.of("message", "Rate multipliers cleared successfully");
    }

    public Map<String, String> batchSetGroupRateMultipliers(long id, BatchSetGroupRateMultipliersRequest request) {
        List<AdminGroupRepository.GroupRateWriteEntry> entries = request.entries().stream()
                .map(entry -> new AdminGroupRepository.GroupRateWriteEntry(entry.user_id(), entry.rate_multiplier()))
                .toList();
        repository.batchSetGroupRateMultipliers(id, entries);
        return Map.of("message", "Rate multipliers updated successfully");
    }

    public Map<String, String> clearGroupRpmOverrides(long id) {
        repository.clearGroupRpmOverrides(id);
        return Map.of("message", "RPM overrides cleared successfully");
    }

    public Map<String, String> batchSetGroupRpmOverrides(long id, BatchSetGroupRpmOverridesRequest request) {
        List<AdminGroupRepository.GroupRpmWriteEntry> entries = request.entries().stream()
                .map(entry -> new AdminGroupRepository.GroupRpmWriteEntry(entry.user_id(), entry.rpm_override()))
                .toList();
        repository.batchSetGroupRpmOverrides(id, entries);
        return Map.of("message", "RPM overrides updated successfully");
    }

    public Map<String, String> updateSortOrder(UpdateGroupSortOrderRequest request) {
        repository.updateGroupSortOrders(request.updates().stream()
                .map(entry -> new AdminGroupRepository.GroupSortOrderEntry(entry.id(), entry.sort_order()))
                .toList());
        return Map.of("message", "Sort order updated successfully");
    }

    public List<GroupUsageSummaryResponse> getUsageSummary(String timezone) {
        return repository.getUsageSummary(resolveTodayStart(timezone));
    }

    public List<GroupCapacitySummaryResponse> getCapacitySummary() {
        return repository.getCapacitySummary();
    }

    private Instant resolveTodayStart(String timezone) {
        ZoneId zoneId;
        try {
            zoneId = timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
        } catch (Exception ex) {
            zoneId = ZoneId.systemDefault();
        }
        return LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant();
    }

    private int computeNextSortOrder() {
        List<AdminGroupResponse> groups = repository.listAllActiveGroups(null);
        int max = groups.stream().mapToInt(AdminGroupResponse::sort_order).max().orElse(0);
        return max + 10;
    }

    private List<Long> collectCopyAccountIds(List<Long> sourceGroupIds, boolean requireOauthOnly) {
        if (sourceGroupIds.isEmpty()) {
            return List.of();
        }
        List<Long> accountIds = repository.getAccountIdsByGroupIds(sourceGroupIds);
        if (!requireOauthOnly || accountIds.isEmpty()) {
            return accountIds;
        }
        Map<Long, AdminGroupRepository.AccountSnapshot> accountsById = new LinkedHashMap<>();
        for (AdminGroupRepository.AccountSnapshot account : repository.getAccountsByIds(accountIds)) {
            accountsById.put(account.id(), account);
        }
        List<Long> filtered = new ArrayList<>();
        for (Long accountId : accountIds) {
            AdminGroupRepository.AccountSnapshot account = accountsById.get(accountId);
            if (account != null && !ACCOUNT_TYPE_APIKEY.equalsIgnoreCase(account.type())) {
                filtered.add(accountId);
            }
        }
        return filtered;
    }

    private List<Long> normalizeCopySourceGroupIds(List<Long> sourceGroupIds, Long currentGroupId, String targetPlatform) {
        if (sourceGroupIds == null || sourceGroupIds.isEmpty()) {
            return List.of();
        }
        List<Long> uniqueIds = new ArrayList<>();
        for (Long sourceGroupId : new LinkedHashSet<>(sourceGroupIds)) {
            if (sourceGroupId == null || sourceGroupId <= 0) {
                continue;
            }
            if (currentGroupId != null && sourceGroupId.equals(currentGroupId)) {
                throw new IllegalArgumentException("cannot copy accounts from self");
            }
            AdminGroupRepository.GroupSnapshot sourceGroup = repository.findFallbackGroup(sourceGroupId)
                    .orElseThrow(() -> new IllegalArgumentException("source group " + sourceGroupId + " not found"));
            if (!normalizePlatform(sourceGroup.platform(), false).equals(targetPlatform)) {
                throw new IllegalArgumentException(
                        "source group " + sourceGroupId + " platform mismatch: expected " + targetPlatform + ", got " + sourceGroup.platform()
                );
            }
            uniqueIds.add(sourceGroupId);
        }
        return uniqueIds;
    }

    private void validateFallbackGroup(long currentGroupId, Long fallbackGroupId) {
        if (fallbackGroupId == null) {
            return;
        }
        if (currentGroupId > 0 && fallbackGroupId == currentGroupId) {
            throw new IllegalArgumentException("cannot set self as fallback group");
        }
        LinkedHashSet<Long> visited = new LinkedHashSet<>();
        Long nextId = fallbackGroupId;
        while (nextId != null) {
            if (!visited.add(nextId) || (currentGroupId > 0 && nextId == currentGroupId)) {
                throw new IllegalArgumentException("fallback group cycle detected");
            }
            AdminGroupRepository.GroupSnapshot fallback = repository.findFallbackGroup(nextId)
                    .orElseThrow(() -> new IllegalArgumentException("fallback group not found"));
            if (nextId.equals(fallbackGroupId) && fallback.claudeCodeOnly()) {
                throw new IllegalArgumentException("fallback group cannot have claude_code_only enabled");
            }
            nextId = fallback.fallbackGroupId();
        }
    }

    private void validateInvalidFallbackGroup(long currentGroupId, String platform, String subscriptionType, Long fallbackGroupId) {
        if (fallbackGroupId == null) {
            return;
        }
        if (!PLATFORM_ANTHROPIC.equals(platform) && !PLATFORM_ANTIGRAVITY.equals(platform)) {
            throw new IllegalArgumentException("invalid request fallback only supported for anthropic or antigravity groups");
        }
        if (SUBSCRIPTION_SUBSCRIPTION.equals(subscriptionType)) {
            throw new IllegalArgumentException("subscription groups cannot set invalid request fallback");
        }
        if (currentGroupId > 0 && fallbackGroupId == currentGroupId) {
            throw new IllegalArgumentException("cannot set self as invalid request fallback group");
        }
        AdminGroupRepository.GroupSnapshot fallback = repository.findFallbackGroup(fallbackGroupId)
                .orElseThrow(() -> new IllegalArgumentException("fallback group not found"));
        if (!PLATFORM_ANTHROPIC.equals(normalizePlatform(fallback.platform(), false))) {
            throw new IllegalArgumentException("fallback group must be anthropic platform");
        }
        if (SUBSCRIPTION_SUBSCRIPTION.equals(fallback.subscriptionType())) {
            throw new IllegalArgumentException("fallback group cannot be subscription type");
        }
        if (fallback.fallbackGroupIdOnInvalidRequest() != null) {
            throw new IllegalArgumentException("fallback group cannot have invalid request fallback configured");
        }
    }

    private AdminGroupResponse.MessagesDispatchModelConfig sanitizeMessagesDispatchConfig(
            String platform,
            AdminGroupResponse.MessagesDispatchModelConfig config
    ) {
        if (!PLATFORM_OPENAI.equals(platform)) {
            return new AdminGroupResponse.MessagesDispatchModelConfig("", "", "", Map.of());
        }
        return config;
    }

    private AdminGroupResponse.MessagesDispatchModelConfig normalizeMessagesDispatchConfig(
            AdminGroupResponse.MessagesDispatchModelConfig config
    ) {
        if (config == null) {
            return new AdminGroupResponse.MessagesDispatchModelConfig(
                    "gpt-5.4",
                    "gpt-5.3-codex",
                    "gpt-5.4-mini",
                    Map.of()
            );
        }
        Map<String, String> exactMappings = new LinkedHashMap<>();
        if (config.exact_model_mappings() != null) {
            for (Map.Entry<String, String> entry : config.exact_model_mappings().entrySet()) {
                String key = normalizeOptionalText(entry.getKey());
                String value = normalizeOptionalText(entry.getValue());
                if (!key.isEmpty() && !value.isEmpty()) {
                    exactMappings.put(key, value);
                }
            }
        }
        return new AdminGroupResponse.MessagesDispatchModelConfig(
                normalizeOptionalTextOrDefault(config.opus_mapped_model(), "gpt-5.4"),
                normalizeOptionalTextOrDefault(config.sonnet_mapped_model(), "gpt-5.3-codex"),
                normalizeOptionalTextOrDefault(config.haiku_mapped_model(), "gpt-5.4-mini"),
                exactMappings
        );
    }

    private Map<String, List<Long>> normalizeModelRouting(Map<String, List<Long>> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        Map<String, List<Long>> routing = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : source.entrySet()) {
            String pattern = normalizeOptionalText(entry.getKey());
            if (pattern.isEmpty()) {
                continue;
            }
            List<Long> ids = (entry.getValue() == null ? List.<Long>of() : entry.getValue()).stream()
                    .filter(id -> id != null && id > 0)
                    .distinct()
                    .toList();
            if (!ids.isEmpty()) {
                routing.put(pattern, ids);
            }
        }
        return routing.isEmpty() ? null : routing;
    }

    private List<String> normalizeSupportedScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return DEFAULT_MODEL_SCOPES;
        }
        List<String> normalized = scopes.stream()
                .map(this::normalizeOptionalText)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        return normalized.isEmpty() ? DEFAULT_MODEL_SCOPES : normalized;
    }

    private String normalizeSearch(String search) {
        if (search == null) {
            return null;
        }
        String normalized = search.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }

    private String normalizeName(String value) {
        String normalized = normalizeOptionalText(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        return normalized;
    }

    private String normalizeDescription(String value) {
        String normalized = normalizeOptionalText(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeOptionalTextOrDefault(String value, String defaultValue) {
        String normalized = normalizeOptionalText(value);
        return normalized.isEmpty() ? defaultValue : normalized;
    }

    private String normalizeOptionalText(String value) {
        return value == null ? "" : value.trim();
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

    private String normalizePlatform(String platform, boolean allowNull) {
        String normalized = normalizeOptionalText(platform).toLowerCase();
        if (normalized.isEmpty()) {
            if (allowNull) {
                return null;
            }
            return PLATFORM_ANTHROPIC;
        }
        if (!SUPPORTED_PLATFORMS.contains(normalized)) {
            throw new IllegalArgumentException("platform is invalid");
        }
        return normalized;
    }

    private String normalizeStatus(String status, boolean allowNull) {
        String normalized = normalizeOptionalText(status).toLowerCase();
        if (normalized.isEmpty()) {
            if (allowNull) {
                return null;
            }
            return STATUS_ACTIVE;
        }
        if (!SUPPORTED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status is invalid");
        }
        return normalized;
    }

    private String normalizeSubscriptionType(String subscriptionType, boolean allowNull) {
        String normalized = normalizeOptionalText(subscriptionType).toLowerCase();
        if (normalized.isEmpty()) {
            if (allowNull) {
                return null;
            }
            return SUBSCRIPTION_STANDARD;
        }
        if (!SUPPORTED_SUBSCRIPTION_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("subscription_type is invalid");
        }
        return normalized;
    }

    private double normalizeCreateRateMultiplier(Double rateMultiplier) {
        if (rateMultiplier == null) {
            return 1.0;
        }
        if (rateMultiplier <= 0) {
            throw new IllegalArgumentException("rate_multiplier must be > 0");
        }
        return rateMultiplier;
    }

    private double normalizeUpdateRateMultiplier(Double rateMultiplier) {
        if (rateMultiplier == null || rateMultiplier <= 0) {
            throw new IllegalArgumentException("rate_multiplier must be > 0");
        }
        return rateMultiplier;
    }

    private Double normalizeLimit(Double limit) {
        if (limit == null || limit < 0) {
            return null;
        }
        return limit;
    }

    private Double normalizePrice(Double price) {
        if (price == null || price < 0) {
            return null;
        }
        return price;
    }

    private double normalizeImageRateMultiplier(Double value) {
        if (value == null) {
            return 1.0;
        }
        if (value < 0) {
            throw new IllegalArgumentException("image_rate_multiplier must be >= 0");
        }
        return value;
    }

    private int normalizeRpmLimit(Integer rpmLimit) {
        if (rpmLimit == null || rpmLimit < 0) {
            return 0;
        }
        return rpmLimit;
    }

    private Long normalizeFallbackId(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }
}
