package org.apiprivaterouter.javabackend.availablechannels.service;

import org.apiprivaterouter.javabackend.availablechannels.model.UserAvailableChannelResponse;
import org.apiprivaterouter.javabackend.availablechannels.model.UserAvailableGroupItemResponse;
import org.apiprivaterouter.javabackend.availablechannels.model.UserChannelPlatformSectionResponse;
import org.apiprivaterouter.javabackend.availablechannels.model.UserPricingIntervalResponse;
import org.apiprivaterouter.javabackend.availablechannels.model.UserSupportedModelPricingResponse;
import org.apiprivaterouter.javabackend.availablechannels.model.UserSupportedModelResponse;
import org.apiprivaterouter.javabackend.availablechannels.repository.AvailableChannelsRepository;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;
import org.apiprivaterouter.javabackend.usergroups.model.UserAvailableGroupResponse;
import org.apiprivaterouter.javabackend.usergroups.service.UserGroupService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class AvailableChannelsService {

    private static final String STATUS_ACTIVE = "active";

    private final AvailableChannelsRepository repository;
    private final UserGroupService userGroupService;
    private final PublicSettingsService publicSettingsService;

    public AvailableChannelsService(
            AvailableChannelsRepository repository,
            UserGroupService userGroupService,
            PublicSettingsService publicSettingsService
    ) {
        this.repository = repository;
        this.userGroupService = userGroupService;
        this.publicSettingsService = publicSettingsService;
    }

    public List<UserAvailableChannelResponse> getAvailableChannels(CurrentUser currentUser) {
        if (!publicSettingsService.getPublicSettings().available_channels_enabled()) {
            return List.of();
        }

        Map<Long, UserAvailableGroupItemResponse> visibleGroupsById = new LinkedHashMap<>();
        for (UserAvailableGroupResponse group : userGroupService.getAvailableGroups(currentUser)) {
            visibleGroupsById.put(group.id(), toGroupItem(group));
        }
        if (visibleGroupsById.isEmpty()) {
            return List.of();
        }

        List<UserAvailableChannelResponse> result = new ArrayList<>();
        for (AvailableChannelsRepository.ChannelRecord channel : repository.listChannels()) {
            if (!STATUS_ACTIVE.equalsIgnoreCase(channel.status())) {
                continue;
            }

            List<UserAvailableGroupItemResponse> visibleGroups = channel.groupIds().stream()
                    .map(visibleGroupsById::get)
                    .filter(Objects::nonNull)
                    .sorted(Comparator
                            .comparing((UserAvailableGroupItemResponse group) -> group.name().toLowerCase(Locale.ROOT))
                            .thenComparingLong(UserAvailableGroupItemResponse::id))
                    .toList();
            if (visibleGroups.isEmpty()) {
                continue;
            }

            List<UserSupportedModelResponse> supportedModels = buildSupportedModels(channel);
            List<UserChannelPlatformSectionResponse> sections = buildPlatformSections(visibleGroups, supportedModels);
            if (sections.isEmpty()) {
                continue;
            }

            result.add(new UserAvailableChannelResponse(
                    channel.name(),
                    channel.description(),
                    sections
            ));
        }

        result.sort(Comparator
                .comparing((UserAvailableChannelResponse channel) -> channel.name().toLowerCase(Locale.ROOT))
                .thenComparing(UserAvailableChannelResponse::name));
        return List.copyOf(result);
    }

    private List<UserSupportedModelResponse> buildSupportedModels(AvailableChannelsRepository.ChannelRecord channel) {
        Map<String, PricingIndex> pricingIndexByPlatform = buildPricingIndex(channel.modelPricing());
        Map<SupportedModelKey, UserSupportedModelResponse> deduped = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, String>> platformEntry : channel.modelMapping().entrySet()) {
            String platform = normalizePlatform(platformEntry.getKey());
            PricingIndex pricingIndex = pricingIndexByPlatform.get(platform);
            for (Map.Entry<String, String> mappingEntry : platformEntry.getValue().entrySet()) {
                String source = normalizeText(mappingEntry.getKey());
                if (source.isEmpty()) {
                    continue;
                }
                WildcardPattern sourcePattern = splitWildcard(source);
                if (sourcePattern.wildcard()) {
                    if (pricingIndex == null) {
                        continue;
                    }
                    String prefixLower = sourcePattern.prefix().toLowerCase(Locale.ROOT);
                    for (String candidate : pricingIndex.names()) {
                        if (candidate.toLowerCase(Locale.ROOT).startsWith(prefixLower)) {
                            LookupResult lookup = lookup(pricingIndex, candidate);
                            addSupportedModel(deduped, platform, lookup.displayName(), lookup.pricing());
                        }
                    }
                    continue;
                }

                String target = normalizeText(mappingEntry.getValue());
                if (target.isEmpty() || splitWildcard(target).wildcard()) {
                    target = source;
                }
                LookupResult pricingLookup = lookup(pricingIndex, target);
                LookupResult displayLookup = lookup(pricingIndex, source);
                addSupportedModel(deduped, platform, displayLookup.displayName(), pricingLookup.pricing());
            }
        }

        for (Map.Entry<String, PricingIndex> entry : pricingIndexByPlatform.entrySet()) {
            for (String modelName : entry.getValue().names()) {
                LookupResult lookup = lookup(entry.getValue(), modelName);
                addSupportedModel(deduped, entry.getKey(), lookup.displayName(), lookup.pricing());
            }
        }

        List<UserSupportedModelResponse> result = new ArrayList<>(deduped.values());
        result.sort(Comparator
                .comparing((UserSupportedModelResponse model) -> model.platform().toLowerCase(Locale.ROOT))
                .thenComparing(model -> model.name().toLowerCase(Locale.ROOT))
                .thenComparing(UserSupportedModelResponse::name));
        return List.copyOf(result);
    }

    private Map<String, PricingIndex> buildPricingIndex(List<AvailableChannelsRepository.PricingRecord> pricingRecords) {
        Map<String, PricingIndexBuilder> builders = new LinkedHashMap<>();
        for (AvailableChannelsRepository.PricingRecord pricing : pricingRecords) {
            String platform = normalizePlatform(pricing.platform());
            PricingIndexBuilder builder = builders.computeIfAbsent(platform, ignored -> new PricingIndexBuilder());
            for (String model : pricing.models()) {
                WildcardPattern pattern = splitWildcard(model);
                if (pattern.wildcard()) {
                    continue;
                }
                String normalizedModel = normalizeText(model);
                if (normalizedModel.isEmpty()) {
                    continue;
                }
                String lowerCase = normalizedModel.toLowerCase(Locale.ROOT);
                if (builder.byLowerCase().containsKey(lowerCase)) {
                    continue;
                }
                builder.byLowerCase().put(lowerCase, pricing);
                builder.originalCase().put(lowerCase, normalizedModel);
                builder.names().add(normalizedModel);
            }
        }

        Map<String, PricingIndex> result = new LinkedHashMap<>();
        for (Map.Entry<String, PricingIndexBuilder> entry : builders.entrySet()) {
            result.put(entry.getKey(), new PricingIndex(
                    Map.copyOf(entry.getValue().byLowerCase()),
                    Map.copyOf(entry.getValue().originalCase()),
                    List.copyOf(entry.getValue().names())
            ));
        }
        return result;
    }

    private LookupResult lookup(PricingIndex pricingIndex, String modelName) {
        if (pricingIndex == null) {
            return new LookupResult(modelName, null);
        }
        String normalizedModel = normalizeText(modelName);
        String lowerCase = normalizedModel.toLowerCase(Locale.ROOT);
        AvailableChannelsRepository.PricingRecord pricing = pricingIndex.byLowerCase().get(lowerCase);
        if (pricing == null) {
            return new LookupResult(normalizedModel, null);
        }
        return new LookupResult(
                pricingIndex.originalCase().getOrDefault(lowerCase, normalizedModel),
                toPricingResponse(pricing)
        );
    }

    private void addSupportedModel(
            Map<SupportedModelKey, UserSupportedModelResponse> deduped,
            String platform,
            String modelName,
            UserSupportedModelPricingResponse pricing
    ) {
        String normalizedModel = normalizeText(modelName);
        if (platform.isEmpty() || normalizedModel.isEmpty()) {
            return;
        }
        SupportedModelKey key = new SupportedModelKey(platform, normalizedModel.toLowerCase(Locale.ROOT));
        deduped.putIfAbsent(key, new UserSupportedModelResponse(normalizedModel, platform, pricing));
    }

    private List<UserChannelPlatformSectionResponse> buildPlatformSections(
            List<UserAvailableGroupItemResponse> visibleGroups,
            List<UserSupportedModelResponse> supportedModels
    ) {
        Map<String, List<UserAvailableGroupItemResponse>> groupsByPlatform = new LinkedHashMap<>();
        for (UserAvailableGroupItemResponse group : visibleGroups) {
            String platform = normalizePlatform(group.platform());
            if (platform.isEmpty()) {
                continue;
            }
            groupsByPlatform.computeIfAbsent(platform, ignored -> new ArrayList<>()).add(group);
        }
        if (groupsByPlatform.isEmpty()) {
            return List.of();
        }

        Map<String, List<UserSupportedModelResponse>> modelsByPlatform = new LinkedHashMap<>();
        for (UserSupportedModelResponse model : supportedModels) {
            String platform = normalizePlatform(model.platform());
            if (platform.isEmpty()) {
                continue;
            }
            modelsByPlatform.computeIfAbsent(platform, ignored -> new ArrayList<>()).add(model);
        }

        List<String> platforms = new ArrayList<>(groupsByPlatform.keySet());
        platforms.sort(String::compareTo);

        List<UserChannelPlatformSectionResponse> sections = new ArrayList<>(platforms.size());
        for (String platform : platforms) {
            List<UserAvailableGroupItemResponse> groups = new ArrayList<>(groupsByPlatform.get(platform));
            groups.sort(Comparator
                    .comparing((UserAvailableGroupItemResponse group) -> group.name().toLowerCase(Locale.ROOT))
                    .thenComparingLong(UserAvailableGroupItemResponse::id));

            List<UserSupportedModelResponse> models = new ArrayList<>(modelsByPlatform.getOrDefault(platform, List.of()));
            models.sort(Comparator
                    .comparing((UserSupportedModelResponse model) -> model.name().toLowerCase(Locale.ROOT))
                    .thenComparing(UserSupportedModelResponse::name));

            sections.add(new UserChannelPlatformSectionResponse(
                    platform,
                    List.copyOf(groups),
                    List.copyOf(models)
            ));
        }
        return List.copyOf(sections);
    }

    private UserAvailableGroupItemResponse toGroupItem(UserAvailableGroupResponse group) {
        return new UserAvailableGroupItemResponse(
                group.id(),
                normalizeText(group.name()),
                normalizePlatform(group.platform()),
                normalizeText(group.subscription_type()),
                group.rate_multiplier(),
                group.is_exclusive()
        );
    }

    private UserSupportedModelPricingResponse toPricingResponse(AvailableChannelsRepository.PricingRecord pricing) {
        List<UserPricingIntervalResponse> intervals = pricing.intervals().stream()
                .map(this::toIntervalResponse)
                .toList();
        return new UserSupportedModelPricingResponse(
                normalizeBillingMode(pricing.billingMode()),
                pricing.inputPrice(),
                pricing.outputPrice(),
                pricing.cacheWritePrice(),
                pricing.cacheReadPrice(),
                pricing.imageOutputPrice(),
                pricing.perRequestPrice(),
                intervals
        );
    }

    private UserPricingIntervalResponse toIntervalResponse(AvailableChannelsRepository.PricingIntervalRecord interval) {
        return new UserPricingIntervalResponse(
                interval.minTokens(),
                interval.maxTokens(),
                interval.tierLabel(),
                interval.inputPrice(),
                interval.outputPrice(),
                interval.cacheWritePrice(),
                interval.cacheReadPrice(),
                interval.perRequestPrice()
        );
    }

    private WildcardPattern splitWildcard(String value) {
        String normalized = normalizeText(value);
        if (normalized.endsWith("*")) {
            return new WildcardPattern(normalized.substring(0, normalized.length() - 1), true);
        }
        return new WildcardPattern(normalized, false);
    }

    private String normalizePlatform(String platform) {
        return normalizeText(platform).toLowerCase(Locale.ROOT);
    }

    private String normalizeBillingMode(String billingMode) {
        String normalized = normalizeText(billingMode).toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "token" : normalized;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private record SupportedModelKey(String platform, String modelNameLowerCase) {
    }

    private record WildcardPattern(String prefix, boolean wildcard) {
    }

    private record LookupResult(String displayName, UserSupportedModelPricingResponse pricing) {
    }

    private record PricingIndex(
            Map<String, AvailableChannelsRepository.PricingRecord> byLowerCase,
            Map<String, String> originalCase,
            List<String> names
    ) {
    }

    private record PricingIndexBuilder(
            Map<String, AvailableChannelsRepository.PricingRecord> byLowerCase,
            Map<String, String> originalCase,
            List<String> names
    ) {
        private PricingIndexBuilder() {
            this(new LinkedHashMap<>(), new LinkedHashMap<>(), new ArrayList<>());
        }
    }
}
