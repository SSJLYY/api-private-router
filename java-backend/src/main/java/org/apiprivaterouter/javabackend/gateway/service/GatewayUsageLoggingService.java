package org.apiprivaterouter.javabackend.gateway.service;

import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.userfund.service.FundService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GatewayUsageLoggingService {

    private static final Logger log = LoggerFactory.getLogger(GatewayUsageLoggingService.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final FundService fundService;
    private final ObjectMapper objectMapper;
    private final BillingCircuitBreaker circuitBreaker;

    public GatewayUsageLoggingService(NamedParameterJdbcTemplate jdbcTemplate, FundService fundService,
                                      ObjectMapper objectMapper, BillingCircuitBreaker circuitBreaker) {
        this.jdbcTemplate = jdbcTemplate;
        this.fundService = fundService;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
    }

    public void logUsage(Long accountId, long userId, String model, String upstreamModel,
                         long inputTokens, long outputTokens, long cacheReadTokens, long cacheCreationTokens,
                         boolean stream, long durationMs, String platform, String endpoint) {
        if (userId <= 0) return;
        try {
            insertUsageLog(userId, 0, accountId != null ? accountId : 0L, UUID.randomUUID().toString(),
                    upstreamModel != null ? upstreamModel : model,
                    (int) Math.min(inputTokens, Integer.MAX_VALUE),
                    (int) Math.min(outputTokens, Integer.MAX_VALUE),
                    (int) Math.min(cacheCreationTokens, Integer.MAX_VALUE),
                    (int) Math.min(cacheReadTokens, Integer.MAX_VALUE),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ONE, BigDecimal.ONE, stream, null);
        } catch (Exception ex) {
            log.warn("simplified usage log failed for userId={}: {}", userId, ex.getMessage());
        }
    }

    public void logUsage(GatewayRuntimeContext ctx, String model, int inputTokens, int outputTokens,
                         int cacheCreationTokens, int cacheReadTokens, boolean stream,
                         String requestId) {
        if (ctx.apiKey() == null || ctx.account() == null) {
            return;
        }
        long userId = ctx.apiKey().userId();
        long apiKeyId = ctx.apiKey().id();
        long accountId = ctx.account().id();
        Long groupId = ctx.apiKey().groupId();

        if (!circuitBreaker.allow()) {
            log.warn("billing circuit breaker is OPEN, skipping billing for userId={}", userId);
            try {
                insertUsageLog(userId, apiKeyId, accountId, requestId, model,
                        inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ONE, BigDecimal.ONE, stream, groupId);
                circuitBreaker.onSuccess();
            } catch (Exception ex) {
                circuitBreaker.onFailure(ex);
                log.warn("usage log insert failed while circuit breaker open for userId={}: {}", userId, ex.getMessage());
            }
            return;
        }

        PricingResult pricing;
        try {
            pricing = lookupPricing(userId, accountId, groupId, ctx.account().platform(), model,
                    inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens);
        } catch (Exception ex) {
            circuitBreaker.onFailure(ex);
            log.error("pricing lookup failed for userId={}: {}", userId, ex.getMessage(), ex);
            return;
        }

        BigDecimal inputCost = BigDecimal.valueOf(inputTokens).multiply(pricing.inputPrice());
        BigDecimal outputCost = BigDecimal.valueOf(outputTokens).multiply(pricing.outputPrice());
        BigDecimal cacheCreationCost = BigDecimal.valueOf(cacheCreationTokens).multiply(pricing.cacheWritePrice());
        BigDecimal cacheReadCost = BigDecimal.valueOf(cacheReadTokens).multiply(pricing.cacheReadPrice());
        BigDecimal perRequestCost = pricing.perRequestPrice();
        BigDecimal totalCost = inputCost.add(outputCost).add(cacheCreationCost).add(cacheReadCost).add(perRequestCost);

        BigDecimal groupMultiplier = pricing.groupMultiplier();
        BigDecimal accountMultiplier = pricing.accountMultiplier();
        BigDecimal actualCost = totalCost.multiply(groupMultiplier).multiply(accountMultiplier)
                .setScale(10, RoundingMode.HALF_UP);

        try {
            long usageLogId = insertUsageLog(userId, apiKeyId, accountId, requestId, model,
                    inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens,
                    inputCost, outputCost, cacheCreationCost, cacheReadCost, totalCost, actualCost,
                    groupMultiplier, accountMultiplier, stream, groupId);
            circuitBreaker.onSuccess();

            if (actualCost.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    long amountInCents = actualCost.movePointRight(8).setScale(0, RoundingMode.HALF_UP).longValue();
                    if (amountInCents > 0) {
                        fundService.deductApiUsage(userId, amountInCents, "usage_log", usageLogId,
                                "API usage: " + model);
                    }
                } catch (Exception ex) {
                    log.error("fund deduction failed for userId={} usageLogId={}: {}", userId, usageLogId, ex.getMessage(), ex);
                }
            }
        } catch (Exception ex) {
            circuitBreaker.onFailure(ex);
            log.error("usage log insert failed for userId={}: {}", userId, ex.getMessage(), ex);
        }
    }

    private PricingResult lookupPricing(long userId, long accountId, Long groupId, String platform, String model,
                                          int inputTokens, int outputTokens,
                                          int cacheCreationTokens, int cacheReadTokens) {
        BigDecimal inputPrice = BigDecimal.ZERO;
        BigDecimal outputPrice = BigDecimal.ZERO;
        BigDecimal cacheWritePrice = BigDecimal.ZERO;
        BigDecimal cacheReadPrice = BigDecimal.ZERO;
        BigDecimal perRequestPrice = BigDecimal.ZERO;
        BigDecimal groupMultiplier = BigDecimal.ONE;
        BigDecimal accountMultiplier = BigDecimal.ONE;

        if (groupId != null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    select cg.channel_id
                    from channel_groups cg
                    where cg.group_id = :groupId
                    limit 1
                    """, new MapSqlParameterSource("groupId", groupId));
            if (!rows.isEmpty()) {
                long channelId = ((Number) rows.get(0).get("channel_id")).longValue();
                Map<String, Object> pricingRow = findPricingForModel(channelId, platform, model, inputTokens + outputTokens);
                if (pricingRow != null) {
                    inputPrice = getBigDecimal(pricingRow, "input_price");
                    outputPrice = getBigDecimal(pricingRow, "output_price");
                    cacheWritePrice = getBigDecimal(pricingRow, "cache_write_price");
                    cacheReadPrice = getBigDecimal(pricingRow, "cache_read_price");
                    perRequestPrice = getBigDecimal(pricingRow, "per_request_price");
                }
            }

            List<Map<String, Object>> groupRows = jdbcTemplate.queryForList("""
                    select coalesce(ugr.rate_multiplier, g.rate_multiplier) as rate_multiplier
                    from groups g
                    left join user_group_rate_multipliers ugr
                      on ugr.group_id = g.id
                     and ugr.user_id = :userId
                    where g.id = :groupId
                    """, new MapSqlParameterSource("groupId", groupId)
                    .addValue("userId", userId));
            Map<String, Object> groupRow = groupRows.isEmpty()
                    ? Map.of("rate_multiplier", BigDecimal.ONE)
                    : groupRows.get(0);
            groupMultiplier = getBigDecimal(groupRow, "rate_multiplier");
            if (groupMultiplier.compareTo(BigDecimal.ZERO) <= 0) {
                groupMultiplier = BigDecimal.ONE;
            }
        }

        List<Map<String, Object>> accountRows = jdbcTemplate.queryForList("""
                select rate_multiplier from accounts where id = :accountId
                """, new MapSqlParameterSource("accountId", accountId));
        if (!accountRows.isEmpty()) {
            accountMultiplier = getBigDecimal(accountRows.get(0), "rate_multiplier");
            if (accountMultiplier.compareTo(BigDecimal.ZERO) < 0) {
                accountMultiplier = BigDecimal.ONE;
            }
        }

        return new PricingResult(inputPrice, outputPrice, cacheWritePrice, cacheReadPrice,
                perRequestPrice, groupMultiplier, accountMultiplier);
    }

    private Map<String, Object> findPricingForModel(long channelId, String platform, String model, int totalTokens) {
        String normalizedPlatform = normalizePlatform(platform);
        List<Map<String, Object>> pricingRows = jdbcTemplate.queryForList("""
                select cmp.id, cmp.input_price, cmp.output_price, cmp.cache_write_price, cmp.cache_read_price,
                       cmp.billing_mode, cmp.per_request_price
                from channel_model_pricing cmp
                where cmp.channel_id = :channelId
                  and (cmp.platform is null or cmp.platform = '' or cmp.platform = :platform)
                  and cmp.models @> cast(:modelJson as jsonb)
                order by case when cmp.platform = :platform then 0 else 1 end, cmp.id asc
                limit 1
                """, new MapSqlParameterSource("channelId", channelId)
                        .addValue("platform", normalizedPlatform)
                        .addValue("modelJson", modelJsonArray(model)));

        if (pricingRows.isEmpty()) {
            return null;
        }

        Map<String, Object> pricing = pricingRows.get(0);
        long pricingId = ((Number) pricing.get("id")).longValue();

        List<Map<String, Object>> intervals = jdbcTemplate.queryForList("""
                select input_price, output_price, cache_write_price, cache_read_price, per_request_price,
                       min_tokens, max_tokens
                from channel_pricing_intervals
                where pricing_id = :pricingId
                  and min_tokens <= :totalTokens
                  and (max_tokens is null or max_tokens >= :totalTokens)
                order by sort_order asc, id asc
                limit 1
                """, new MapSqlParameterSource("pricingId", pricingId)
                        .addValue("totalTokens", totalTokens));

        if (!intervals.isEmpty()) {
            Map<String, Object> interval = intervals.get(0);
            Map<String, Object> merged = new LinkedHashMap<>();
            merged.put("input_price", interval.get("input_price") != null ? interval.get("input_price") : pricing.get("input_price"));
            merged.put("output_price", interval.get("output_price") != null ? interval.get("output_price") : pricing.get("output_price"));
            merged.put("cache_write_price", interval.get("cache_write_price") != null ? interval.get("cache_write_price") : pricing.get("cache_write_price"));
            merged.put("cache_read_price", interval.get("cache_read_price") != null ? interval.get("cache_read_price") : pricing.get("cache_read_price"));
            return merged;
        }

        return pricing;
    }

    private long insertUsageLog(long userId, long apiKeyId, long accountId, String requestId, String model,
                                int inputTokens, int outputTokens, int cacheCreationTokens, int cacheReadTokens,
                                BigDecimal inputCost, BigDecimal outputCost, BigDecimal cacheCreationCost, BigDecimal cacheReadCost,
                                BigDecimal totalCost, BigDecimal actualCost,
                                BigDecimal groupMultiplier, BigDecimal accountMultiplier,
                                boolean stream, Long groupId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("apiKeyId", apiKeyId)
                .addValue("accountId", accountId)
                .addValue("requestId", requestId != null ? requestId : UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .addValue("model", model)
                .addValue("inputTokens", inputTokens)
                .addValue("outputTokens", outputTokens)
                .addValue("cacheCreationTokens", cacheCreationTokens)
                .addValue("cacheReadTokens", cacheReadTokens)
                .addValue("inputCost", inputCost)
                .addValue("outputCost", outputCost)
                .addValue("cacheCreationCost", cacheCreationCost)
                .addValue("cacheReadCost", cacheReadCost)
                .addValue("totalCost", totalCost)
                .addValue("actualCost", actualCost)
                .addValue("stream", stream)
                .addValue("groupMultiplier", groupMultiplier)
                .addValue("accountMultiplier", accountMultiplier)
                .addValue("groupId", groupId)
                .addValue("createdAt", OffsetDateTime.now());

        return jdbcTemplate.queryForObject("""
                insert into usage_logs (user_id, api_key_id, account_id, request_id, model,
                    input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens,
                    input_cost, output_cost, cache_creation_cost, cache_read_cost,
                    total_cost, actual_cost, stream, rate_multiplier, account_rate_multiplier,
                    group_id, created_at)
                values (:userId, :apiKeyId, :accountId, :requestId, :model,
                    :inputTokens, :outputTokens, :cacheCreationTokens, :cacheReadTokens,
                    :inputCost, :outputCost, :cacheCreationCost, :cacheReadCost,
                    :totalCost, :actualCost, :stream, :groupMultiplier, :accountMultiplier,
                    :groupId, :createdAt)
                returning id
                """, params, Long.class);
    }

    private String normalizePlatform(String platform) {
        if (platform == null) return "";
        String p = platform.trim().toLowerCase();
        if ("antigravity".equals(p)) return "anthropic";
        return p;
    }

    private String modelJsonArray(String value) {
        try {
            return objectMapper.writeValueAsString(List.of(value == null ? "" : value));
        } catch (Exception e) {
            return "[\"\"]";
        }
    }

    private BigDecimal getBigDecimal(Map<String, Object> row, String key) {
        Object val = row.get(key);
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(val));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private record PricingResult(
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            BigDecimal cacheWritePrice,
            BigDecimal cacheReadPrice,
            BigDecimal perRequestPrice,
            BigDecimal groupMultiplier,
            BigDecimal accountMultiplier
    ) {
    }
}
