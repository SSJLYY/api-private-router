package org.apiprivaterouter.javabackend.availablechannels.repository;

import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class AvailableChannelsRepository {

    private static final String DEFAULT_PLATFORM = "anthropic";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AvailableChannelsRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public List<ChannelRecord> listChannels() {
        List<ChannelRow> channels = jdbcTemplate.query("""
                select c.id,
                       c.name,
                       c.description,
                       c.status,
                       c.model_mapping::text as model_mapping
                from channels c
                order by c.id asc
                """, new MapSqlParameterSource(), (rs, rowNum) -> new ChannelRow(
                rs.getLong("id"),
                defaultString(rs.getString("name")),
                defaultString(rs.getString("description")),
                defaultString(rs.getString("status")),
                rs.getString("model_mapping")
        ));
        if (channels.isEmpty()) {
            return List.of();
        }

        List<Long> channelIds = channels.stream().map(ChannelRow::id).toList();
        Map<Long, List<Long>> groupIdsByChannel = loadGroupIds(channelIds);
        Map<Long, List<PricingRecord>> pricingByChannel = loadPricing(channelIds);

        List<ChannelRecord> result = new ArrayList<>(channels.size());
        for (ChannelRow channel : channels) {
            result.add(new ChannelRecord(
                    channel.id(),
                    channel.name(),
                    channel.description(),
                    channel.status(),
                    parseModelMapping(channel.modelMapping()),
                    groupIdsByChannel.getOrDefault(channel.id(), List.of()),
                    pricingByChannel.getOrDefault(channel.id(), List.of())
            ));
        }
        return List.copyOf(result);
    }

    private Map<Long, List<Long>> loadGroupIds(List<Long> channelIds) {
        if (channelIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> groupIdsByChannel = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select channel_id, group_id
                from channel_groups
                where channel_id in (:channelIds)
                order by channel_id asc, group_id asc
                """, new MapSqlParameterSource("channelIds", channelIds), rs -> {
            long channelId = rs.getLong("channel_id");
            groupIdsByChannel.computeIfAbsent(channelId, ignored -> new ArrayList<>()).add(rs.getLong("group_id"));
        });
        return groupIdsByChannel;
    }

    private Map<Long, List<PricingRecord>> loadPricing(List<Long> channelIds) {
        if (channelIds.isEmpty()) {
            return Map.of();
        }
        List<PricingRow> pricingRows = jdbcTemplate.query("""
                select cmp.id,
                       cmp.channel_id,
                       cmp.platform,
                       cmp.models::text as models,
                       cmp.billing_mode,
                       cmp.input_price,
                       cmp.output_price,
                       cmp.cache_write_price,
                       cmp.cache_read_price,
                       cmp.image_output_price,
                       cmp.per_request_price
                from channel_model_pricing cmp
                where cmp.channel_id in (:channelIds)
                order by cmp.channel_id asc, cmp.id asc
                """, new MapSqlParameterSource("channelIds", channelIds), (rs, rowNum) -> new PricingRow(
                rs.getLong("id"),
                rs.getLong("channel_id"),
                normalizePlatform(rs.getString("platform")),
                jsonHelper.readStringList(rs.getString("models")),
                defaultString(rs.getString("billing_mode")),
                rs.getObject("input_price", Double.class),
                rs.getObject("output_price", Double.class),
                rs.getObject("cache_write_price", Double.class),
                rs.getObject("cache_read_price", Double.class),
                rs.getObject("image_output_price", Double.class),
                rs.getObject("per_request_price", Double.class)
        ));
        if (pricingRows.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<PricingIntervalRecord>> intervalsByPricing = loadIntervals(
                pricingRows.stream().map(PricingRow::id).toList()
        );

        Map<Long, List<PricingRecord>> pricingByChannel = new LinkedHashMap<>();
        for (PricingRow pricing : pricingRows) {
            pricingByChannel.computeIfAbsent(pricing.channelId(), ignored -> new ArrayList<>()).add(new PricingRecord(
                    pricing.id(),
                    pricing.platform(),
                    List.copyOf(pricing.models()),
                    normalizeBillingMode(pricing.billingMode()),
                    pricing.inputPrice(),
                    pricing.outputPrice(),
                    pricing.cacheWritePrice(),
                    pricing.cacheReadPrice(),
                    pricing.imageOutputPrice(),
                    pricing.perRequestPrice(),
                    List.copyOf(intervalsByPricing.getOrDefault(pricing.id(), List.of()))
            ));
        }
        return pricingByChannel;
    }

    private Map<Long, List<PricingIntervalRecord>> loadIntervals(List<Long> pricingIds) {
        if (pricingIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PricingIntervalRecord>> intervalsByPricing = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select pricing_id,
                       min_tokens,
                       max_tokens,
                       tier_label,
                       input_price,
                       output_price,
                       cache_write_price,
                       cache_read_price,
                       per_request_price
                from channel_pricing_intervals
                where pricing_id in (:pricingIds)
                order by pricing_id asc, sort_order asc, id asc
                """, new MapSqlParameterSource("pricingIds", pricingIds), rs -> {
            long pricingId = rs.getLong("pricing_id");
            intervalsByPricing.computeIfAbsent(pricingId, ignored -> new ArrayList<>()).add(new PricingIntervalRecord(
                    rs.getInt("min_tokens"),
                    rs.getObject("max_tokens", Integer.class),
                    blankToNull(rs.getString("tier_label")),
                    rs.getObject("input_price", Double.class),
                    rs.getObject("output_price", Double.class),
                    rs.getObject("cache_write_price", Double.class),
                    rs.getObject("cache_read_price", Double.class),
                    rs.getObject("per_request_price", Double.class)
            ));
        });
        return intervalsByPricing;
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
            if (!(entry.getValue() instanceof Map<?, ?> nestedMap)) {
                continue;
            }
            Map<String, String> converted = toStringMap(nestedMap);
            if (!converted.isEmpty()) {
                result.put(normalizePlatform(entry.getKey()), converted);
            }
        }
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    private Map<String, String> toStringMap(Map<?, ?> source) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = blankToNull(String.valueOf(entry.getKey()));
            String value = blankToNull(entry.getValue() == null ? null : String.valueOf(entry.getValue()));
            if (key == null || value == null) {
                continue;
            }
            result.put(key, value);
        }
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    private String normalizePlatform(String platform) {
        String normalized = blankToNull(platform);
        return normalized == null ? DEFAULT_PLATFORM : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeBillingMode(String billingMode) {
        String normalized = blankToNull(billingMode);
        return normalized == null ? "token" : normalized.toLowerCase(Locale.ROOT);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record ChannelRow(
            long id,
            String name,
            String description,
            String status,
            String modelMapping
    ) {
    }

    public record ChannelRecord(
            long id,
            String name,
            String description,
            String status,
            Map<String, Map<String, String>> modelMapping,
            List<Long> groupIds,
            List<PricingRecord> modelPricing
    ) {
    }

    private record PricingRow(
            long id,
            long channelId,
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

    public record PricingRecord(
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
            int minTokens,
            Integer maxTokens,
            String tierLabel,
            Double inputPrice,
            Double outputPrice,
            Double cacheWritePrice,
            Double cacheReadPrice,
            Double perRequestPrice
    ) {
    }
}
