package org.apiprivaterouter.javabackend.admin.channels.service;

import org.springframework.stereotype.Service;
import org.apiprivaterouter.javabackend.admin.channels.model.AccountStatsPricingRuleRequest;
import org.apiprivaterouter.javabackend.admin.channels.model.AccountStatsPricingRuleResponse;
import org.apiprivaterouter.javabackend.admin.channels.model.AdminChannelResponse;
import org.apiprivaterouter.javabackend.admin.channels.model.ChannelModelPricingRequest;
import org.apiprivaterouter.javabackend.admin.channels.model.ChannelModelPricingResponse;
import org.apiprivaterouter.javabackend.admin.channels.model.CreateAdminChannelRequest;
import org.apiprivaterouter.javabackend.admin.channels.model.ModelDefaultPricingResponse;
import org.apiprivaterouter.javabackend.admin.channels.model.PricingIntervalRequest;
import org.apiprivaterouter.javabackend.admin.channels.model.PricingIntervalResponse;
import org.apiprivaterouter.javabackend.admin.channels.model.UpdateAdminChannelRequest;
import org.apiprivaterouter.javabackend.admin.channels.repository.AdminChannelsRepository;
import org.apiprivaterouter.javabackend.common.api.PageResponse;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AdminChannelsService {

    private static final Set<String> ALLOWED_STATUS = Set.of("active", "disabled");
    private static final Set<String> ALLOWED_BILLING_MODEL_SOURCE = Set.of("requested", "upstream", "channel_mapped");
    private static final Set<String> ALLOWED_BILLING_MODE = Set.of("token", "per_request", "image");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final AdminChannelsRepository repository;

    public AdminChannelsService(AdminChannelsRepository repository) {
        this.repository = repository;
    }

    public PageResponse<AdminChannelResponse> listChannels(
            int page,
            int pageSize,
            String status,
            String search,
            String sortBy,
            String sortOrder
    ) {
        String normalizedStatus = normalizeOptional(status);
        if (normalizedStatus != null && !ALLOWED_STATUS.contains(normalizedStatus)) {
            throw new IllegalArgumentException("status is invalid");
        }
        AdminChannelsRepository.PageResult<AdminChannelsRepository.ChannelRecord> result = repository.listChannels(
                page, pageSize, normalizedStatus, normalizeOptional(search), sortBy, sortOrder
        );
        List<AdminChannelResponse> items = result.items().stream().map(this::toResponse).toList();
        return new PageResponse<>(items, result.total(), result.page(), result.pageSize());
    }

    public AdminChannelResponse getChannel(long id) {
        AdminChannelsRepository.ChannelRecord record = repository.getChannel(id);
        if (record == null) {
            throw new IllegalArgumentException("channel not found");
        }
        return toResponse(record);
    }

    public AdminChannelResponse createChannel(CreateAdminChannelRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String name = requireName(request.name());
        if (repository.existsByName(name)) {
            throw new IllegalArgumentException("channel name already exists");
        }
        List<Long> groupIds = normalizeIdList(request.group_ids());
        ensureNoGroupConflict(0L, groupIds);

        List<AdminChannelsRepository.ModelPricingWriteModel> modelPricing = toModelPricingWriteModels(request.model_pricing());
        Map<String, Map<String, String>> modelMapping = normalizeModelMapping(request.model_mapping());
        validateChannelConfig(modelPricing, modelMapping);

        List<AdminChannelsRepository.AccountStatsPricingRuleWriteModel> statsRules =
                toAccountStatsPricingRules(request.account_stats_pricing_rules());

        long id = repository.createChannel(new AdminChannelsRepository.ChannelWriteModel(
                name,
                defaultString(request.description()),
                "active",
                normalizeBillingModelSource(request.billing_model_source()),
                Boolean.TRUE.equals(request.restrict_models()),
                "",
                normalizeFeaturesConfig(request.features_config()),
                groupIds,
                modelPricing,
                modelMapping,
                Boolean.TRUE.equals(request.apply_pricing_to_account_stats()),
                statsRules
        ));
        return getChannel(id);
    }

    public AdminChannelResponse updateChannel(long id, UpdateAdminChannelRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        AdminChannelsRepository.ChannelRecord existing = repository.getChannel(id);
        if (existing == null) {
            throw new IllegalArgumentException("channel not found");
        }

        String name = defaultString(existing.name());
        if (normalizeOptional(request.name()) != null) {
            name = requireName(request.name());
            if (!name.equalsIgnoreCase(existing.name()) && repository.existsByNameExcluding(name, id)) {
                throw new IllegalArgumentException("channel name already exists");
            }
        }

        String status = existing.status();
        if (normalizeOptional(request.status()) != null) {
            status = normalizeOptional(request.status());
            if (!ALLOWED_STATUS.contains(status)) {
                throw new IllegalArgumentException("status is invalid");
            }
        }

        List<Long> groupIds = existing.groupIds();
        if (request.group_ids() != null) {
            groupIds = normalizeIdList(request.group_ids());
            ensureNoGroupConflict(id, groupIds);
        }

        List<AdminChannelsRepository.ModelPricingWriteModel> modelPricing = toModelPricingWriteModelsFromExisting(
                request.model_pricing(), existing.modelPricing()
        );
        Map<String, Map<String, String>> modelMapping = request.model_mapping() == null
                ? existing.modelMapping()
                : normalizeModelMapping(request.model_mapping());
        validateChannelConfig(modelPricing, modelMapping);

        List<AdminChannelsRepository.AccountStatsPricingRuleWriteModel> statsRules = request.account_stats_pricing_rules() == null
                ? toAccountStatsPricingRuleWriteModels(existing.accountStatsPricingRules())
                : toAccountStatsPricingRules(request.account_stats_pricing_rules());

        repository.updateChannel(id, new AdminChannelsRepository.ChannelWriteModel(
                name,
                request.description() == null ? existing.description() : defaultString(request.description()),
                status,
                normalizeOptional(request.billing_model_source()) == null
                        ? existing.billingModelSource()
                        : normalizeBillingModelSource(request.billing_model_source()),
                request.restrict_models() == null ? existing.restrictModels() : request.restrict_models(),
                existing.features(),
                request.features_config() == null ? existing.featuresConfig() : normalizeFeaturesConfig(request.features_config()),
                groupIds,
                modelPricing,
                modelMapping,
                request.apply_pricing_to_account_stats() == null
                        ? existing.applyPricingToAccountStats()
                        : request.apply_pricing_to_account_stats(),
                statsRules
        ));
        return getChannel(id);
    }

    public Map<String, String> deleteChannel(long id) {
        repository.deleteChannel(id);
        return Map.of("message", "Channel deleted successfully");
    }

    public ModelDefaultPricingResponse getModelDefaultPricing(String model) {
        String normalizedModel = normalizeOptional(model);
        if (normalizedModel == null) {
            throw new IllegalArgumentException("model parameter is required");
        }
        Map<String, Object> pricing = repository.findFallbackModelPricing(normalizedModel);
        if (pricing.isEmpty()) {
            return new ModelDefaultPricingResponse(false, null, null, null, null, null);
        }
        return new ModelDefaultPricingResponse(
                true,
                toDouble(pricing.get("input_cost_per_token")),
                toDouble(pricing.get("output_cost_per_token")),
                toDouble(pricing.get("cache_creation_input_token_cost")),
                toDouble(pricing.get("cache_read_input_token_cost")),
                toDouble(pricing.get("output_cost_per_image_token"))
        );
    }

    private void ensureNoGroupConflict(long channelId, List<Long> groupIds) {
        List<Long> conflicting = repository.findConflictingGroupIds(channelId, groupIds);
        if (!conflicting.isEmpty()) {
            throw new IllegalArgumentException("one or more groups already belong to another channel");
        }
    }

    private AdminChannelResponse toResponse(AdminChannelsRepository.ChannelRecord record) {
        return new AdminChannelResponse(
                record.id(),
                record.name(),
                record.description(),
                record.status(),
                record.billingModelSource(),
                record.restrictModels(),
                record.featuresConfig(),
                List.copyOf(record.groupIds()),
                record.modelPricing().stream().map(this::toPricingResponse).toList(),
                record.modelMapping(),
                record.applyPricingToAccountStats(),
                record.accountStatsPricingRules().stream().map(this::toRuleResponse).toList(),
                formatTimestamp(record.createdAt()),
                formatTimestamp(record.updatedAt())
        );
    }

    private ChannelModelPricingResponse toPricingResponse(AdminChannelsRepository.ModelPricingRecord pricing) {
        return new ChannelModelPricingResponse(
                pricing.id(),
                pricing.platform(),
                List.copyOf(pricing.models()),
                pricing.billingMode(),
                pricing.inputPrice(),
                pricing.outputPrice(),
                pricing.cacheWritePrice(),
                pricing.cacheReadPrice(),
                pricing.imageOutputPrice(),
                pricing.perRequestPrice(),
                pricing.intervals().stream().map(this::toIntervalResponse).toList()
        );
    }

    private PricingIntervalResponse toIntervalResponse(AdminChannelsRepository.PricingIntervalRecord interval) {
        return new PricingIntervalResponse(
                interval.id(),
                interval.minTokens(),
                interval.maxTokens(),
                interval.tierLabel() == null ? "" : interval.tierLabel(),
                interval.inputPrice(),
                interval.outputPrice(),
                interval.cacheWritePrice(),
                interval.cacheReadPrice(),
                interval.perRequestPrice(),
                interval.sortOrder()
        );
    }

    private AccountStatsPricingRuleResponse toRuleResponse(AdminChannelsRepository.AccountStatsPricingRuleRecord rule) {
        return new AccountStatsPricingRuleResponse(
                rule.id(),
                rule.name(),
                List.copyOf(rule.groupIds()),
                List.copyOf(rule.accountIds()),
                rule.pricing().stream().map(this::toPricingResponse).toList()
        );
    }

    private List<AdminChannelsRepository.ModelPricingWriteModel> toModelPricingWriteModels(List<ChannelModelPricingRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        List<AdminChannelsRepository.ModelPricingWriteModel> result = new ArrayList<>(requests.size());
        for (ChannelModelPricingRequest request : requests) {
            result.add(toModelPricingWriteModel(request));
        }
        return List.copyOf(result);
    }

    private List<AdminChannelsRepository.ModelPricingWriteModel> toModelPricingWriteModelsFromExisting(
            List<ChannelModelPricingRequest> requests,
            List<AdminChannelsRepository.ModelPricingRecord> existing
    ) {
        if (requests == null) {
            return toModelPricingWriteModelsFromRecords(existing);
        }
        return toModelPricingWriteModels(requests);
    }

    private List<AdminChannelsRepository.ModelPricingWriteModel> toModelPricingWriteModelsFromRecords(
            List<AdminChannelsRepository.ModelPricingRecord> records
    ) {
        if (records == null) {
            return List.of();
        }
        List<AdminChannelsRepository.ModelPricingWriteModel> result = new ArrayList<>(records.size());
        for (AdminChannelsRepository.ModelPricingRecord record : records) {
            result.add(new AdminChannelsRepository.ModelPricingWriteModel(
                    record.platform(),
                    List.copyOf(record.models()),
                    record.billingMode(),
                    record.inputPrice(),
                    record.outputPrice(),
                    record.cacheWritePrice(),
                    record.cacheReadPrice(),
                    record.imageOutputPrice(),
                    record.perRequestPrice(),
                    record.intervals().stream()
                            .map(interval -> new AdminChannelsRepository.PricingIntervalWriteModel(
                                    interval.minTokens(),
                                    interval.maxTokens(),
                                    interval.tierLabel(),
                                    interval.inputPrice(),
                                    interval.outputPrice(),
                                    interval.cacheWritePrice(),
                                    interval.cacheReadPrice(),
                                    interval.perRequestPrice(),
                                    interval.sortOrder()
                            ))
                            .toList()
            ));
        }
        return List.copyOf(result);
    }

    private AdminChannelsRepository.ModelPricingWriteModel toModelPricingWriteModel(ChannelModelPricingRequest request) {
        List<String> models = normalizeModelNames(request == null ? null : request.models());
        if (models.isEmpty()) {
            throw new IllegalArgumentException("model_pricing models is required");
        }
        String billingMode = normalizeBillingMode(request == null ? null : request.billing_mode());
        List<AdminChannelsRepository.PricingIntervalWriteModel> intervals = toIntervalWriteModels(
                request == null ? null : request.intervals()
        );
        return new AdminChannelsRepository.ModelPricingWriteModel(
                normalizePlatform(request == null ? null : request.platform()),
                models,
                billingMode,
                request == null ? null : request.input_price(),
                request == null ? null : request.output_price(),
                request == null ? null : request.cache_write_price(),
                request == null ? null : request.cache_read_price(),
                request == null ? null : request.image_output_price(),
                request == null ? null : request.per_request_price(),
                intervals
        );
    }

    private List<AdminChannelsRepository.PricingIntervalWriteModel> toIntervalWriteModels(List<PricingIntervalRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        List<AdminChannelsRepository.PricingIntervalWriteModel> result = new ArrayList<>(requests.size());
        for (PricingIntervalRequest request : requests) {
            result.add(new AdminChannelsRepository.PricingIntervalWriteModel(
                    request.min_tokens(),
                    request.max_tokens(),
                    request.tier_label(),
                    request.input_price(),
                    request.output_price(),
                    request.cache_write_price(),
                    request.cache_read_price(),
                    request.per_request_price(),
                    request.sort_order()
            ));
        }
        return List.copyOf(result);
    }

    private List<AdminChannelsRepository.AccountStatsPricingRuleWriteModel> toAccountStatsPricingRules(
            List<AccountStatsPricingRuleRequest> requests
    ) {
        if (requests == null) {
            return List.of();
        }
        List<AdminChannelsRepository.AccountStatsPricingRuleWriteModel> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            AccountStatsPricingRuleRequest request = requests.get(i);
            List<Long> groupIds = normalizeIdList(request.group_ids());
            List<Long> accountIds = normalizeIdList(request.account_ids());
            if (groupIds.isEmpty() && accountIds.isEmpty()) {
                throw new IllegalArgumentException("pricing rule #%d must have at least one group or account".formatted(i + 1));
            }
            List<AdminChannelsRepository.ModelPricingWriteModel> pricing = toModelPricingWriteModels(request.pricing());
            if (pricing.isEmpty()) {
                throw new IllegalArgumentException("pricing rule #%d must have at least one pricing entry".formatted(i + 1));
            }
            validatePricingEntries(pricing, "account stats pricing rule #%d".formatted(i + 1));
            result.add(new AdminChannelsRepository.AccountStatsPricingRuleWriteModel(
                    defaultString(request.name()),
                    groupIds,
                    accountIds,
                    pricing
            ));
        }
        return List.copyOf(result);
    }

    private List<AdminChannelsRepository.AccountStatsPricingRuleWriteModel> toAccountStatsPricingRuleWriteModels(
            List<AdminChannelsRepository.AccountStatsPricingRuleRecord> records
    ) {
        if (records == null) {
            return List.of();
        }
        List<AdminChannelsRepository.AccountStatsPricingRuleWriteModel> result = new ArrayList<>(records.size());
        for (AdminChannelsRepository.AccountStatsPricingRuleRecord record : records) {
            result.add(new AdminChannelsRepository.AccountStatsPricingRuleWriteModel(
                    record.name(),
                    List.copyOf(record.groupIds()),
                    List.copyOf(record.accountIds()),
                    toModelPricingWriteModelsFromRecords(record.pricing())
            ));
        }
        return List.copyOf(result);
    }

    private void validateChannelConfig(
            List<AdminChannelsRepository.ModelPricingWriteModel> pricing,
            Map<String, Map<String, String>> mapping
    ) {
        validatePricingEntries(pricing, "channel pricing");
        validateNoConflictingMappings(mapping);
    }

    private void validatePricingEntries(
            List<AdminChannelsRepository.ModelPricingWriteModel> pricing,
            String label
    ) {
        validateNoConflictingModels(pricing, label);
        validatePricingIntervals(pricing, label);
        validatePricingBillingMode(pricing, label);
    }

    private void validateNoConflictingModels(
            List<AdminChannelsRepository.ModelPricingWriteModel> pricing,
            String label
    ) {
        Map<String, List<ModelEntry>> byPlatform = new LinkedHashMap<>();
        for (AdminChannelsRepository.ModelPricingWriteModel entry : pricing) {
            for (String model : entry.models()) {
                byPlatform.computeIfAbsent(entry.platform(), ignored -> new ArrayList<>()).add(toModelEntry(model));
            }
        }
        for (Map.Entry<String, List<ModelEntry>> entry : byPlatform.entrySet()) {
            detectConflicts(entry.getValue(), entry.getKey(), "%s model patterns".formatted(label));
        }
    }

    private void validateNoConflictingMappings(Map<String, Map<String, String>> mapping) {
        for (Map.Entry<String, Map<String, String>> entry : mapping.entrySet()) {
            List<ModelEntry> entries = new ArrayList<>();
            for (String source : entry.getValue().keySet()) {
                entries.add(toModelEntry(source));
            }
            detectConflicts(entries, entry.getKey(), "mapping source patterns");
        }
    }

    private void detectConflicts(List<ModelEntry> entries, String platform, String label) {
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                if (conflictsBetween(entries.get(i), entries.get(j))) {
                    throw new IllegalArgumentException(
                            "%s '%s' and '%s' conflict in platform '%s': overlapping match range".formatted(
                                    label, entries.get(i).pattern(), entries.get(j).pattern(), platform
                            )
                    );
                }
            }
        }
    }

    private void validatePricingIntervals(
            List<AdminChannelsRepository.ModelPricingWriteModel> pricing,
            String label
    ) {
        for (AdminChannelsRepository.ModelPricingWriteModel item : pricing) {
            validateIntervals(item.intervals(), label + " for platform '" + item.platform() + "' models " + item.models());
        }
    }

    private void validateIntervals(
            List<AdminChannelsRepository.PricingIntervalWriteModel> intervals,
            String label
    ) {
        if (intervals == null || intervals.isEmpty()) {
            return;
        }
        List<AdminChannelsRepository.PricingIntervalWriteModel> sorted = new ArrayList<>(intervals);
        sorted.sort((left, right) -> Integer.compare(left.minTokens(), right.minTokens()));
        for (int i = 0; i < sorted.size(); i++) {
            AdminChannelsRepository.PricingIntervalWriteModel interval = sorted.get(i);
            if (interval.minTokens() < 0) {
                throw new IllegalArgumentException("%s interval #%d: min_tokens (%d) must be >= 0".formatted(
                        label, i + 1, interval.minTokens()
                ));
            }
            if (interval.maxTokens() != null) {
                if (interval.maxTokens() <= 0) {
                    throw new IllegalArgumentException("%s interval #%d: max_tokens (%d) must be > 0".formatted(
                            label, i + 1, interval.maxTokens()
                    ));
                }
                if (interval.maxTokens() <= interval.minTokens()) {
                    throw new IllegalArgumentException("%s interval #%d: max_tokens (%d) must be > min_tokens (%d)".formatted(
                            label, i + 1, interval.maxTokens(), interval.minTokens()
                    ));
                }
            }
            validateIntervalPrices(interval, label, i + 1);
            if (i > 0) {
                AdminChannelsRepository.PricingIntervalWriteModel previous = sorted.get(i - 1);
                if (previous.maxTokens() == null) {
                    throw new IllegalArgumentException("%s interval #%d: unbounded interval (max_tokens=null) must be the last one".formatted(
                            label, i
                    ));
                }
                if (previous.maxTokens() > interval.minTokens()) {
                    throw new IllegalArgumentException("%s interval #%d and #%d overlap".formatted(label, i, i + 1));
                }
            }
        }
    }

    private void validateIntervalPrices(
            AdminChannelsRepository.PricingIntervalWriteModel interval,
            String label,
            int index
    ) {
        checkNonNegative(interval.inputPrice(), "%s interval #%d: input_price must be >= 0".formatted(label, index));
        checkNonNegative(interval.outputPrice(), "%s interval #%d: output_price must be >= 0".formatted(label, index));
        checkNonNegative(interval.cacheWritePrice(), "%s interval #%d: cache_write_price must be >= 0".formatted(label, index));
        checkNonNegative(interval.cacheReadPrice(), "%s interval #%d: cache_read_price must be >= 0".formatted(label, index));
        checkNonNegative(interval.perRequestPrice(), "%s interval #%d: per_request_price must be >= 0".formatted(label, index));
    }

    private void validatePricingBillingMode(
            List<AdminChannelsRepository.ModelPricingWriteModel> pricing,
            String label
    ) {
        for (AdminChannelsRepository.ModelPricingWriteModel item : pricing) {
            if (!ALLOWED_BILLING_MODE.contains(item.billingMode())) {
                throw new IllegalArgumentException("billing_mode is invalid");
            }
            if (("per_request".equals(item.billingMode()) || "image".equals(item.billingMode()))
                    && item.perRequestPrice() == null
                    && (item.intervals() == null || item.intervals().isEmpty())) {
                throw new IllegalArgumentException("per-request price or intervals required for per_request/image billing mode");
            }
            checkNonNegative(item.inputPrice(), label + " input_price must be >= 0");
            checkNonNegative(item.outputPrice(), label + " output_price must be >= 0");
            checkNonNegative(item.cacheWritePrice(), label + " cache_write_price must be >= 0");
            checkNonNegative(item.cacheReadPrice(), label + " cache_read_price must be >= 0");
            checkNonNegative(item.imageOutputPrice(), label + " image_output_price must be >= 0");
            checkNonNegative(item.perRequestPrice(), label + " per_request_price must be >= 0");

            if (item.intervals() != null) {
                for (AdminChannelsRepository.PricingIntervalWriteModel interval : item.intervals()) {
                    if (interval.inputPrice() == null
                            && interval.outputPrice() == null
                            && interval.cacheWritePrice() == null
                            && interval.cacheReadPrice() == null
                            && interval.perRequestPrice() == null) {
                        throw new IllegalArgumentException("interval has no price fields set for model " + item.models());
                    }
                }
            }
        }
    }

    private void checkNonNegative(Double value, String message) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private Map<String, Map<String, String>> normalizeModelMapping(Map<String, Map<String, String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : source.entrySet()) {
            String platform = normalizePlatform(entry.getKey());
            Map<String, String> mappings = new LinkedHashMap<>();
            if (entry.getValue() != null) {
                for (Map.Entry<String, String> mappingEntry : entry.getValue().entrySet()) {
                    String key = normalizeOptional(mappingEntry.getKey());
                    String value = normalizeOptional(mappingEntry.getValue());
                    if (key != null && value != null) {
                        mappings.put(key, value);
                    }
                }
            }
            if (!mappings.isEmpty()) {
                result.put(platform, Map.copyOf(mappings));
            }
        }
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    private Map<String, Object> normalizeFeaturesConfig(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    private List<Long> normalizeIdList(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long value : values) {
            if (value != null && value > 0) {
                unique.add(value);
            }
        }
        return unique.isEmpty() ? List.of() : List.copyOf(unique);
    }

    private List<String> normalizeModelNames(List<String> models) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String model : models) {
            String normalized = normalizeOptional(model);
            if (normalized != null) {
                unique.add(normalized);
            }
        }
        return unique.isEmpty() ? List.of() : List.copyOf(unique);
    }

    private String normalizePlatform(String platform) {
        String normalized = normalizeOptional(platform);
        return normalized == null ? "anthropic" : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeBillingMode(String billingMode) {
        String normalized = normalizeOptional(billingMode);
        if (normalized == null) {
            return "token";
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!ALLOWED_BILLING_MODE.contains(lower)) {
            throw new IllegalArgumentException("billing_mode is invalid");
        }
        return lower;
    }

    private String normalizeBillingModelSource(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return "channel_mapped";
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!ALLOWED_BILLING_MODEL_SOURCE.contains(lower)) {
            throw new IllegalArgumentException("billing_model_source is invalid");
        }
        return lower;
    }

    private String requireName(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException("name is required");
        }
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("name is too long");
        }
        return normalized;
    }

    private String formatTimestamp(OffsetDateTime value) {
        return value == null ? "" : TIMESTAMP_FORMATTER.format(value);
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean conflictsBetween(ModelEntry left, ModelEntry right) {
        if (!left.wildcard() && !right.wildcard()) {
            return left.prefix().equals(right.prefix());
        }
        if (left.wildcard() && !right.wildcard()) {
            return right.prefix().startsWith(left.prefix());
        }
        if (!left.wildcard()) {
            return left.prefix().startsWith(right.prefix());
        }
        return left.prefix().startsWith(right.prefix()) || right.prefix().startsWith(left.prefix());
    }

    private ModelEntry toModelEntry(String pattern) {
        String normalized = normalizeOptional(pattern);
        if (normalized == null) {
            return new ModelEntry("", "", false);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith("*")) {
            return new ModelEntry(normalized, lower.substring(0, lower.length() - 1), true);
        }
        return new ModelEntry(normalized, lower, false);
    }

    private record ModelEntry(String pattern, String prefix, boolean wildcard) {
    }
}
