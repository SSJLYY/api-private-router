package org.apiprivaterouter.javabackend.admin.channels.repository;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Types;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Repository
public class AdminChannelsRepository {

    private static final String DEFAULT_PLATFORM = "anthropic";
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "name", "status", "created_at");
    private static final Set<String> ALLOWED_SORT_ORDER = Set.of("asc", "desc");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AdminChannelsRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public PageResult<ChannelRecord> listChannels(
            int page,
            int pageSize,
            String status,
            String search,
            String sortBy,
            String sortOrder
    ) {
        String normalizedStatus = blankToNull(status);
        String normalizedSearch = blankToNull(search);
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));

        StringBuilder where = new StringBuilder(" where 1=1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (normalizedStatus != null) {
            where.append(" and c.status = :status ");
            params.addValue("status", normalizedStatus);
        }
        if (normalizedSearch != null) {
            where.append(" and (c.name ilike :search or c.description ilike :search) ");
            params.addValue("search", "%" + escapeLike(normalizedSearch) + "%");
        }

        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from channels c
                """ + where, params, Long.class);
        long totalCount = total == null ? 0L : total;

        String safeSortBy = normalizeSortBy(sortBy);
        String safeSortOrder = normalizeSortOrder(sortOrder);
        params.addValue("limit", safePageSize);
        params.addValue("offset", (safePage - 1) * safePageSize);

        List<ChannelRow> rows = jdbcTemplate.query("""
                select c.id,
                       c.name,
                       c.description,
                       c.status,
                       c.model_mapping::text as model_mapping,
                       c.billing_model_source,
                       c.restrict_models,
                       c.features,
                       c.features_config::text as features_config,
                       c.apply_pricing_to_account_stats,
                       c.created_at,
                       c.updated_at
                from channels c
                """ + where + """
                order by c.""" + safeSortBy + " " + safeSortOrder + ", c.id " + safeSortOrder + """
                 limit :limit offset :offset
                """, params, (rs, rowNum) -> new ChannelRow(
                rs.getLong("id"),
                defaultString(rs.getString("name")),
                defaultString(rs.getString("description")),
                defaultString(rs.getString("status")),
                rs.getString("model_mapping"),
                defaultString(rs.getString("billing_model_source")),
                Boolean.TRUE.equals(rs.getObject("restrict_models", Boolean.class)),
                defaultString(rs.getString("features")),
                rs.getString("features_config"),
                Boolean.TRUE.equals(rs.getObject("apply_pricing_to_account_stats", Boolean.class)),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        ));

        List<ChannelRecord> channels = hydrateChannels(rows);
        return new PageResult<>(channels, totalCount, safePage, safePageSize);
    }

    public ChannelRecord getChannel(long id) {
        List<ChannelRow> rows = jdbcTemplate.query("""
                select c.id,
                       c.name,
                       c.description,
                       c.status,
                       c.model_mapping::text as model_mapping,
                       c.billing_model_source,
                       c.restrict_models,
                       c.features,
                       c.features_config::text as features_config,
                       c.apply_pricing_to_account_stats,
                       c.created_at,
                       c.updated_at
                from channels c
                where c.id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> new ChannelRow(
                rs.getLong("id"),
                defaultString(rs.getString("name")),
                defaultString(rs.getString("description")),
                defaultString(rs.getString("status")),
                rs.getString("model_mapping"),
                defaultString(rs.getString("billing_model_source")),
                Boolean.TRUE.equals(rs.getObject("restrict_models", Boolean.class)),
                defaultString(rs.getString("features")),
                rs.getString("features_config"),
                Boolean.TRUE.equals(rs.getObject("apply_pricing_to_account_stats", Boolean.class)),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        ));
        if (rows.isEmpty()) {
            return null;
        }
        return hydrateChannels(rows).get(0);
    }

    public boolean existsByName(String name) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1 from channels where lower(name) = lower(:name)
                )
                """, new MapSqlParameterSource("name", name), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public boolean existsByNameExcluding(String name, long excludeId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1 from channels where lower(name) = lower(:name) and id <> :excludeId
                )
                """, new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("excludeId", excludeId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public List<Long> findConflictingGroupIds(long channelId, List<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                select group_id
                from channel_groups
                where group_id in (:groupIds)
                  and channel_id <> :channelId
                order by group_id asc
                """, new MapSqlParameterSource()
                .addValue("groupIds", groupIds)
                .addValue("channelId", channelId), (rs, rowNum) -> rs.getLong("group_id"));
    }

    @Transactional
    public long createChannel(ChannelWriteModel model) {
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                    insert into channels (
                        name, description, status, model_mapping, billing_model_source, restrict_models,
                        features, features_config, apply_pricing_to_account_stats, created_at, updated_at
                    ) values (
                        :name, :description, :status, cast(:modelMapping as jsonb), :billingModelSource, :restrictModels,
                        :features, cast(:featuresConfig as jsonb), :applyPricingToAccountStats, now(), now()
                    )
                    returning id
                    """, channelWriteParams(model), keyHolder, new String[]{"id"});
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("failed to create channel");
            }
            long channelId = key.longValue();
            replaceGroupIds(channelId, model.groupIds());
            replaceModelPricing(channelId, model.modelPricing());
            replaceAccountStatsPricingRules(channelId, model.accountStatsPricingRules());
            return channelId;
        } catch (DataIntegrityViolationException ex) {
            if (isUniqueViolation(ex)) {
                throw new IllegalArgumentException("channel name already exists");
            }
            throw ex;
        }
    }

    @Transactional
    public void updateChannel(long id, ChannelWriteModel model) {
        try {
            int updated = jdbcTemplate.update("""
                    update channels
                    set name = :name,
                        description = :description,
                        status = :status,
                        model_mapping = cast(:modelMapping as jsonb),
                        billing_model_source = :billingModelSource,
                        restrict_models = :restrictModels,
                        features = :features,
                        features_config = cast(:featuresConfig as jsonb),
                        apply_pricing_to_account_stats = :applyPricingToAccountStats,
                        updated_at = now()
                    where id = :id
                    """, channelWriteParams(model).addValue("id", id));
            if (updated == 0) {
                throw new IllegalArgumentException("channel not found");
            }
            replaceGroupIds(id, model.groupIds());
            replaceModelPricing(id, model.modelPricing());
            replaceAccountStatsPricingRules(id, model.accountStatsPricingRules());
        } catch (DataIntegrityViolationException ex) {
            if (isUniqueViolation(ex)) {
                throw new IllegalArgumentException("channel name already exists");
            }
            throw ex;
        }
    }

    public void deleteChannel(long id) {
        int updated = jdbcTemplate.update("""
                delete from channels
                where id = :id
                """, new MapSqlParameterSource("id", id));
        if (updated == 0) {
            throw new IllegalArgumentException("channel not found");
        }
    }

    public Map<String, Object> findFallbackModelPricing(String model) {
        String normalized = blankToNull(model);
        if (normalized == null) {
            return Map.of();
        }
        Resource resource = resolveModelPricingResource();
        if (resource == null || !resource.exists()) {
            return Map.of();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            String raw = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> all = jsonHelper.readObjectMap(raw);
            Object value = all.get(normalized);
            if (value instanceof Map<?, ?> mapValue) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                    if (entry.getKey() != null) {
                        result.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                return Map.copyOf(result);
            }
            return Map.of();
        } catch (IOException ex) {
            return Map.of();
        }
    }

    private Resource resolveModelPricingResource() {
        FileSystemResource fs = new FileSystemResource("java-backend/src/main/resources/model-pricing/model_prices_and_context_window.json");
        if (fs.exists()) {
            return fs;
        }
        return new ClassPathResource("model-pricing/model_prices_and_context_window.json");
    }

    private void replaceGroupIds(long channelId, List<Long> groupIds) {
        jdbcTemplate.update("""
                delete from channel_groups
                where channel_id = :channelId
                """, new MapSqlParameterSource("channelId", channelId));
        if (groupIds == null || groupIds.isEmpty()) {
            return;
        }
        List<Long> uniqueGroupIds = new ArrayList<>(new LinkedHashSet<>(groupIds));
        List<MapSqlParameterSource> batch = new ArrayList<>(uniqueGroupIds.size());
        for (Long groupId : uniqueGroupIds) {
            batch.add(new MapSqlParameterSource()
                    .addValue("channelId", channelId)
                    .addValue("groupId", groupId));
        }
        jdbcTemplate.batchUpdate("""
                insert into channel_groups (channel_id, group_id)
                values (:channelId, :groupId)
                """, batch.toArray(MapSqlParameterSource[]::new));
    }

    private void replaceModelPricing(long channelId, List<ModelPricingWriteModel> pricingList) {
        jdbcTemplate.update("""
                delete from channel_model_pricing
                where channel_id = :channelId
                """, new MapSqlParameterSource("channelId", channelId));
        if (pricingList == null || pricingList.isEmpty()) {
            return;
        }
        for (ModelPricingWriteModel pricing : pricingList) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                    insert into channel_model_pricing (
                        channel_id, platform, models, billing_mode, input_price, output_price,
                        cache_write_price, cache_read_price, image_output_price, per_request_price,
                        created_at, updated_at
                    ) values (
                        :channelId, :platform, cast(:models as jsonb), :billingMode, :inputPrice, :outputPrice,
                        :cacheWritePrice, :cacheReadPrice, :imageOutputPrice, :perRequestPrice,
                        now(), now()
                    )
                    returning id
                    """, new MapSqlParameterSource()
                    .addValue("channelId", channelId)
                    .addValue("platform", normalizePlatform(pricing.platform()))
                    .addValue("models", jsonHelper.writeJson(pricing.models()))
                    .addValue("billingMode", normalizeBillingMode(pricing.billingMode()))
                    .addValue("inputPrice", pricing.inputPrice())
                    .addValue("outputPrice", pricing.outputPrice())
                    .addValue("cacheWritePrice", pricing.cacheWritePrice())
                    .addValue("cacheReadPrice", pricing.cacheReadPrice())
                    .addValue("imageOutputPrice", pricing.imageOutputPrice())
                    .addValue("perRequestPrice", pricing.perRequestPrice()), keyHolder, new String[]{"id"});
            Number pricingId = keyHolder.getKey();
            if (pricingId == null) {
                throw new IllegalStateException("failed to create model pricing");
            }
            insertPricingIntervals("channel_pricing_intervals", "pricing_id", pricingId.longValue(), pricing.intervals());
        }
    }

    private void replaceAccountStatsPricingRules(long channelId, List<AccountStatsPricingRuleWriteModel> rules) {
        jdbcTemplate.update("""
                delete from channel_account_stats_pricing_rules
                where channel_id = :channelId
                """, new MapSqlParameterSource("channelId", channelId));
        if (rules == null || rules.isEmpty()) {
            return;
        }
        for (int index = 0; index < rules.size(); index++) {
            AccountStatsPricingRuleWriteModel rule = rules.get(index);
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                    insert into channel_account_stats_pricing_rules (
                        channel_id, name, group_ids, account_ids, sort_order, created_at, updated_at
                    ) values (
                        :channelId, :name, :groupIds, :accountIds, :sortOrder, now(), now()
                    )
                    returning id
                    """, new MapSqlParameterSource()
                    .addValue("channelId", channelId)
                    .addValue("name", defaultString(rule.name()))
                    .addValue("groupIds", toSqlLongArray(rule.groupIds()))
                    .addValue("accountIds", toSqlLongArray(rule.accountIds()))
                    .addValue("sortOrder", index), keyHolder, new String[]{"id"});
            Number ruleId = keyHolder.getKey();
            if (ruleId == null) {
                throw new IllegalStateException("failed to create account stats pricing rule");
            }
            replaceAccountStatsModelPricing(ruleId.longValue(), rule.pricing());
        }
    }

    private void replaceAccountStatsModelPricing(long ruleId, List<ModelPricingWriteModel> pricingList) {
        if (pricingList == null || pricingList.isEmpty()) {
            return;
        }
        for (ModelPricingWriteModel pricing : pricingList) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                    insert into channel_account_stats_model_pricing (
                        rule_id, platform, models, billing_mode, input_price, output_price,
                        cache_write_price, cache_read_price, image_output_price, per_request_price,
                        created_at, updated_at
                    ) values (
                        :ruleId, :platform, cast(:models as jsonb), :billingMode, :inputPrice, :outputPrice,
                        :cacheWritePrice, :cacheReadPrice, :imageOutputPrice, :perRequestPrice,
                        now(), now()
                    )
                    returning id
                    """, new MapSqlParameterSource()
                    .addValue("ruleId", ruleId)
                    .addValue("platform", normalizePlatform(pricing.platform()))
                    .addValue("models", jsonHelper.writeJson(pricing.models()))
                    .addValue("billingMode", normalizeBillingMode(pricing.billingMode()))
                    .addValue("inputPrice", pricing.inputPrice())
                    .addValue("outputPrice", pricing.outputPrice())
                    .addValue("cacheWritePrice", pricing.cacheWritePrice())
                    .addValue("cacheReadPrice", pricing.cacheReadPrice())
                    .addValue("imageOutputPrice", pricing.imageOutputPrice())
                    .addValue("perRequestPrice", pricing.perRequestPrice()), keyHolder, new String[]{"id"});
            Number pricingId = keyHolder.getKey();
            if (pricingId == null) {
                throw new IllegalStateException("failed to create account stats model pricing");
            }
            insertPricingIntervals("channel_account_stats_pricing_intervals", "pricing_id", pricingId.longValue(), pricing.intervals());
        }
    }

    private void insertPricingIntervals(
            String tableName,
            String pricingIdColumn,
            long pricingId,
            List<PricingIntervalWriteModel> intervals
    ) {
        if (intervals == null || intervals.isEmpty()) {
            return;
        }
        String sql = """
                insert into %s
                (%s, min_tokens, max_tokens, tier_label, input_price, output_price,
                 cache_write_price, cache_read_price, per_request_price, sort_order, created_at, updated_at)
                values (
                    :pricingId, :minTokens, :maxTokens, :tierLabel, :inputPrice, :outputPrice,
                    :cacheWritePrice, :cacheReadPrice, :perRequestPrice, :sortOrder, now(), now()
                )
                """.formatted(tableName, pricingIdColumn);
        for (PricingIntervalWriteModel interval : intervals) {
            jdbcTemplate.update(sql, new MapSqlParameterSource()
                    .addValue("pricingId", pricingId)
                    .addValue("minTokens", interval.minTokens())
                    .addValue("maxTokens", interval.maxTokens())
                    .addValue("tierLabel", blankToNull(interval.tierLabel()))
                    .addValue("inputPrice", interval.inputPrice())
                    .addValue("outputPrice", interval.outputPrice())
                    .addValue("cacheWritePrice", interval.cacheWritePrice())
                    .addValue("cacheReadPrice", interval.cacheReadPrice())
                    .addValue("perRequestPrice", interval.perRequestPrice())
                    .addValue("sortOrder", interval.sortOrder()));
        }
    }

    private List<ChannelRecord> hydrateChannels(List<ChannelRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> channelIds = rows.stream().map(ChannelRow::id).toList();
        Map<Long, List<Long>> groupIdsByChannel = loadGroupIds(channelIds);
        Map<Long, List<ModelPricingRecord>> modelPricingByChannel = loadModelPricing(channelIds);
        Map<Long, List<AccountStatsPricingRuleRecord>> accountStatsByChannel = loadAccountStatsPricingRules(channelIds);

        List<ChannelRecord> result = new ArrayList<>(rows.size());
        for (ChannelRow row : rows) {
            result.add(new ChannelRecord(
                    row.id(),
                    row.name(),
                    row.description(),
                    row.status(),
                    normalizeBillingModelSource(row.billingModelSource()),
                    row.restrictModels(),
                    parseFeaturesConfig(row.featuresConfig()),
                    groupIdsByChannel.getOrDefault(row.id(), List.of()),
                    modelPricingByChannel.getOrDefault(row.id(), List.of()),
                    parseModelMapping(row.modelMapping()),
                    row.applyPricingToAccountStats(),
                    accountStatsByChannel.getOrDefault(row.id(), List.of()),
                    row.features(),
                    row.createdAt(),
                    row.updatedAt()
            ));
        }
        return List.copyOf(result);
    }

    private Map<Long, List<Long>> loadGroupIds(List<Long> channelIds) {
        if (channelIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select channel_id, group_id
                from channel_groups
                where channel_id in (:channelIds)
                order by channel_id asc, group_id asc
                """, new MapSqlParameterSource("channelIds", channelIds), rs -> {
            long channelId = rs.getLong("channel_id");
            result.computeIfAbsent(channelId, ignored -> new ArrayList<>()).add(rs.getLong("group_id"));
        });
        return result;
    }

    private Map<Long, List<ModelPricingRecord>> loadModelPricing(List<Long> channelIds) {
        if (channelIds.isEmpty()) {
            return Map.of();
        }
        List<ModelPricingRow> rows = jdbcTemplate.query("""
                select id,
                       channel_id,
                       platform,
                       models::text as models,
                       billing_mode,
                       input_price,
                       output_price,
                       cache_write_price,
                       cache_read_price,
                       image_output_price,
                       per_request_price
                from channel_model_pricing
                where channel_id in (:channelIds)
                order by channel_id asc, id asc
                """, new MapSqlParameterSource("channelIds", channelIds), (rs, rowNum) -> new ModelPricingRow(
                rs.getLong("id"),
                rs.getLong("channel_id"),
                normalizePlatform(rs.getString("platform")),
                jsonHelper.readStringList(rs.getString("models")),
                normalizeBillingMode(rs.getString("billing_mode")),
                rs.getObject("input_price", Double.class),
                rs.getObject("output_price", Double.class),
                rs.getObject("cache_write_price", Double.class),
                rs.getObject("cache_read_price", Double.class),
                rs.getObject("image_output_price", Double.class),
                rs.getObject("per_request_price", Double.class)
        ));
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PricingIntervalRecord>> intervalsByPricing = loadIntervals(
                "channel_pricing_intervals",
                rows.stream().map(ModelPricingRow::id).toList()
        );
        Map<Long, List<ModelPricingRecord>> result = new LinkedHashMap<>();
        for (ModelPricingRow row : rows) {
            result.computeIfAbsent(row.ownerId(), ignored -> new ArrayList<>()).add(new ModelPricingRecord(
                    row.id(),
                    row.platform(),
                    List.copyOf(row.models()),
                    row.billingMode(),
                    row.inputPrice(),
                    row.outputPrice(),
                    row.cacheWritePrice(),
                    row.cacheReadPrice(),
                    row.imageOutputPrice(),
                    row.perRequestPrice(),
                    List.copyOf(intervalsByPricing.getOrDefault(row.id(), List.of()))
            ));
        }
        return result;
    }

    private Map<Long, List<AccountStatsPricingRuleRecord>> loadAccountStatsPricingRules(List<Long> channelIds) {
        if (channelIds.isEmpty()) {
            return Map.of();
        }
        List<AccountStatsRuleRow> rules = jdbcTemplate.query("""
                select id,
                       channel_id,
                       name,
                       group_ids,
                       account_ids,
                       sort_order
                from channel_account_stats_pricing_rules
                where channel_id in (:channelIds)
                order by channel_id asc, sort_order asc, id asc
                """, new MapSqlParameterSource("channelIds", channelIds), (rs, rowNum) -> new AccountStatsRuleRow(
                rs.getLong("id"),
                rs.getLong("channel_id"),
                defaultString(rs.getString("name")),
                readLongArray(rs.getArray("group_ids")),
                readLongArray(rs.getArray("account_ids")),
                rs.getInt("sort_order")
        ));
        if (rules.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ModelPricingRecord>> pricingByRule = loadAccountStatsModelPricing(
                rules.stream().map(AccountStatsRuleRow::id).toList()
        );
        Map<Long, List<AccountStatsPricingRuleRecord>> result = new LinkedHashMap<>();
        for (AccountStatsRuleRow row : rules) {
            result.computeIfAbsent(row.channelId(), ignored -> new ArrayList<>()).add(new AccountStatsPricingRuleRecord(
                    row.id(),
                    row.name(),
                    List.copyOf(row.groupIds()),
                    List.copyOf(row.accountIds()),
                    List.copyOf(pricingByRule.getOrDefault(row.id(), List.of()))
            ));
        }
        return result;
    }

    private Map<Long, List<ModelPricingRecord>> loadAccountStatsModelPricing(List<Long> ruleIds) {
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        List<ModelPricingRow> rows = jdbcTemplate.query("""
                select id,
                       rule_id,
                       platform,
                       models::text as models,
                       billing_mode,
                       input_price,
                       output_price,
                       cache_write_price,
                       cache_read_price,
                       image_output_price,
                       per_request_price
                from channel_account_stats_model_pricing
                where rule_id in (:ruleIds)
                order by rule_id asc, id asc
                """, new MapSqlParameterSource("ruleIds", ruleIds), (rs, rowNum) -> new ModelPricingRow(
                rs.getLong("id"),
                rs.getLong("rule_id"),
                normalizePlatform(rs.getString("platform")),
                jsonHelper.readStringList(rs.getString("models")),
                normalizeBillingMode(rs.getString("billing_mode")),
                rs.getObject("input_price", Double.class),
                rs.getObject("output_price", Double.class),
                rs.getObject("cache_write_price", Double.class),
                rs.getObject("cache_read_price", Double.class),
                rs.getObject("image_output_price", Double.class),
                rs.getObject("per_request_price", Double.class)
        ));
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PricingIntervalRecord>> intervalsByPricing = loadIntervals(
                "channel_account_stats_pricing_intervals",
                rows.stream().map(ModelPricingRow::id).toList()
        );
        Map<Long, List<ModelPricingRecord>> result = new LinkedHashMap<>();
        for (ModelPricingRow row : rows) {
            result.computeIfAbsent(row.ownerId(), ignored -> new ArrayList<>()).add(new ModelPricingRecord(
                    row.id(),
                    row.platform(),
                    List.copyOf(row.models()),
                    row.billingMode(),
                    row.inputPrice(),
                    row.outputPrice(),
                    row.cacheWritePrice(),
                    row.cacheReadPrice(),
                    row.imageOutputPrice(),
                    row.perRequestPrice(),
                    List.copyOf(intervalsByPricing.getOrDefault(row.id(), List.of()))
            ));
        }
        return result;
    }

    private Map<Long, List<PricingIntervalRecord>> loadIntervals(String tableName, List<Long> pricingIds) {
        if (pricingIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PricingIntervalRecord>> result = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select id,
                       pricing_id,
                       min_tokens,
                       max_tokens,
                       tier_label,
                       input_price,
                       output_price,
                       cache_write_price,
                       cache_read_price,
                       per_request_price,
                       sort_order
                from """ + tableName + """
                where pricing_id in (:pricingIds)
                order by pricing_id asc, sort_order asc, id asc
                """, new MapSqlParameterSource("pricingIds", pricingIds), rs -> {
            long pricingId = rs.getLong("pricing_id");
            result.computeIfAbsent(pricingId, ignored -> new ArrayList<>()).add(new PricingIntervalRecord(
                    rs.getLong("id"),
                    rs.getInt("min_tokens"),
                    rs.getObject("max_tokens", Integer.class),
                    blankToNull(rs.getString("tier_label")),
                    rs.getObject("input_price", Double.class),
                    rs.getObject("output_price", Double.class),
                    rs.getObject("cache_write_price", Double.class),
                    rs.getObject("cache_read_price", Double.class),
                    rs.getObject("per_request_price", Double.class),
                    rs.getInt("sort_order")
            ));
        });
        return result;
    }

    private MapSqlParameterSource channelWriteParams(ChannelWriteModel model) {
        return new MapSqlParameterSource()
                .addValue("name", model.name())
                .addValue("description", defaultString(model.description()))
                .addValue("status", defaultString(model.status()))
                .addValue("modelMapping", jsonHelper.writeJson(model.modelMapping() == null ? Map.of() : model.modelMapping()))
                .addValue("billingModelSource", normalizeBillingModelSource(model.billingModelSource()))
                .addValue("restrictModels", model.restrictModels())
                .addValue("features", defaultString(model.features()))
                .addValue("featuresConfig", jsonHelper.writeJson(model.featuresConfig() == null ? Map.of() : model.featuresConfig()))
                .addValue("applyPricingToAccountStats", model.applyPricingToAccountStats());
    }

    private Map<String, Object> parseFeaturesConfig(String raw) {
        Map<String, Object> config = jsonHelper.readObjectMap(raw);
        return config.isEmpty() ? Map.of() : Map.copyOf(config);
    }

    private Map<String, Map<String, String>> parseModelMapping(String raw) {
        Map<String, Object> parsed = jsonHelper.readObjectMap(raw);
        if (parsed.isEmpty()) {
            return Map.of();
        }
        boolean nested = parsed.values().stream().anyMatch(value -> value instanceof Map<?, ?>);
        if (!nested) {
            Map<String, String> legacy = toStringMap(parsed);
            return legacy.isEmpty() ? Map.of() : Map.of(DEFAULT_PLATFORM, legacy);
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> nestedMap) {
                Map<String, String> converted = toStringMap(nestedMap);
                if (!converted.isEmpty()) {
                    result.put(normalizePlatform(entry.getKey()), converted);
                }
            }
        }
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    private Map<String, String> toStringMap(Map<?, ?> source) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = blankToNull(entry.getKey() == null ? null : String.valueOf(entry.getKey()));
            String value = blankToNull(entry.getValue() == null ? null : String.valueOf(entry.getValue()));
            if (key != null && value != null) {
                result.put(key, value);
            }
        }
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    private List<Long> readLongArray(java.sql.Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            Object value = array.getArray();
            if (value instanceof Long[] longs) {
                return List.of(longs);
            }
            if (value instanceof Number[] numbers) {
                List<Long> result = new ArrayList<>(numbers.length);
                for (Number number : numbers) {
                    result.add(number.longValue());
                }
                return List.copyOf(result);
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.of();
    }

    private SqlParameterValue toSqlLongArray(List<Long> values) {
        Long[] normalized = values == null
                ? new Long[0]
                : values.stream().filter(Objects::nonNull).toArray(Long[]::new);
        return new SqlParameterValue(Types.ARRAY, normalized);
    }

    private String normalizeBillingModelSource(String source) {
        String normalized = blankToNull(source);
        if (normalized == null) {
            return "channel_mapped";
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizePlatform(String platform) {
        String normalized = blankToNull(platform);
        return normalized == null ? DEFAULT_PLATFORM : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeBillingMode(String billingMode) {
        String normalized = blankToNull(billingMode);
        return normalized == null ? "token" : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeSortBy(String sortBy) {
        String normalized = blankToNull(sortBy);
        if (normalized == null) {
            return "created_at";
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return ALLOWED_SORT_FIELDS.contains(lower) ? lower : "created_at";
    }

    private String normalizeSortOrder(String sortOrder) {
        String normalized = blankToNull(sortOrder);
        if (normalized == null) {
            return "desc";
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return ALLOWED_SORT_ORDER.contains(lower) ? lower : "desc";
    }

    private boolean isUniqueViolation(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if ("23505".equals(extractSqlState(cause))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String extractSqlState(Throwable cause) {
        try {
            Object value = cause.getClass().getMethod("getSQLState").invoke(cause);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private record ChannelRow(
            long id,
            String name,
            String description,
            String status,
            String modelMapping,
            String billingModelSource,
            boolean restrictModels,
            String features,
            String featuresConfig,
            boolean applyPricingToAccountStats,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    private record ModelPricingRow(
            long id,
            long ownerId,
            String platform,
            List<String> models,
            String billingMode,
            Double inputPrice,
            Double outputPrice,
            Double cacheWritePrice,
            Double cacheReadPrice,
            Double imageOutputPrice,
            Double perRequestPrice
    ) {
    }

    private record AccountStatsRuleRow(
            long id,
            long channelId,
            String name,
            List<Long> groupIds,
            List<Long> accountIds,
            int sortOrder
    ) {
    }

    public record ChannelRecord(
            long id,
            String name,
            String description,
            String status,
            String billingModelSource,
            boolean restrictModels,
            Map<String, Object> featuresConfig,
            List<Long> groupIds,
            List<ModelPricingRecord> modelPricing,
            Map<String, Map<String, String>> modelMapping,
            boolean applyPricingToAccountStats,
            List<AccountStatsPricingRuleRecord> accountStatsPricingRules,
            String features,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record ModelPricingRecord(
            long id,
            String platform,
            List<String> models,
            String billingMode,
            Double inputPrice,
            Double outputPrice,
            Double cacheWritePrice,
            Double cacheReadPrice,
            Double imageOutputPrice,
            Double perRequestPrice,
            List<PricingIntervalRecord> intervals
    ) {
    }

    public record PricingIntervalRecord(
            long id,
            int minTokens,
            Integer maxTokens,
            String tierLabel,
            Double inputPrice,
            Double outputPrice,
            Double cacheWritePrice,
            Double cacheReadPrice,
            Double perRequestPrice,
            int sortOrder
    ) {
    }

    public record AccountStatsPricingRuleRecord(
            long id,
            String name,
            List<Long> groupIds,
            List<Long> accountIds,
            List<ModelPricingRecord> pricing
    ) {
    }

    public record ChannelWriteModel(
            String name,
            String description,
            String status,
            String billingModelSource,
            boolean restrictModels,
            String features,
            Map<String, Object> featuresConfig,
            List<Long> groupIds,
            List<ModelPricingWriteModel> modelPricing,
            Map<String, Map<String, String>> modelMapping,
            boolean applyPricingToAccountStats,
            List<AccountStatsPricingRuleWriteModel> accountStatsPricingRules
    ) {
    }

    public record ModelPricingWriteModel(
            String platform,
            List<String> models,
            String billingMode,
            Double inputPrice,
            Double outputPrice,
            Double cacheWritePrice,
            Double cacheReadPrice,
            Double imageOutputPrice,
            Double perRequestPrice,
            List<PricingIntervalWriteModel> intervals
    ) {
    }

    public record PricingIntervalWriteModel(
            int minTokens,
            Integer maxTokens,
            String tierLabel,
            Double inputPrice,
            Double outputPrice,
            Double cacheWritePrice,
            Double cacheReadPrice,
            Double perRequestPrice,
            int sortOrder
    ) {
    }

    public record AccountStatsPricingRuleWriteModel(
            String name,
            List<Long> groupIds,
            List<Long> accountIds,
            List<ModelPricingWriteModel> pricing
    ) {
    }

    public record PageResult<T>(
            List<T> items,
            long total,
            int page,
            int pageSize
    ) {
    }
}
