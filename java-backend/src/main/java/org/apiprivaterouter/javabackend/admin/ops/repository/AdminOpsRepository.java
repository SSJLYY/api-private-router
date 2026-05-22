package org.apiprivaterouter.javabackend.admin.ops.repository;

import org.apiprivaterouter.javabackend.admin.settings.repository.AdminSettingsRepository;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class AdminOpsRepository {

    public static final String KEY_EMAIL_NOTIFICATION_CONFIG = "ops_email_notification_config";
    public static final String KEY_RUNTIME_ALERT_SETTINGS = "ops_alert_runtime_settings";
    public static final String KEY_RUNTIME_LOG_CONFIG = "ops_runtime_log_config";
    public static final String KEY_ADVANCED_SETTINGS = "ops_advanced_settings";
    public static final String KEY_METRIC_THRESHOLDS = "ops_metric_thresholds";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AdminSettingsRepository settingsRepository;
    private final JsonHelper jsonHelper;

    public AdminOpsRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            AdminSettingsRepository settingsRepository,
            JsonHelper jsonHelper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.settingsRepository = settingsRepository;
        this.jsonHelper = jsonHelper;
    }

    public Map<String, Object> loadJsonSetting(String key) {
        return new LinkedHashMap<>(jsonHelper.readObjectMap(settingsRepository.getSettingValue(key)));
    }

    public void saveJsonSetting(String key, Map<String, Object> value) {
        settingsRepository.upsertSettingValue(key, jsonHelper.writeJson(value == null ? Map.of() : value));
    }

    public void deleteSetting(String key) {
        settingsRepository.deleteSetting(key);
    }

    public boolean isMonitoringEnabled() {
        String raw = settingsRepository.getSettingValue("ops_monitoring_enabled");
        if (raw == null) {
            return true;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return !"false".equals(normalized)
                && !"0".equals(normalized)
                && !"off".equals(normalized)
                && !"disabled".equals(normalized);
    }

    public Map<String, Object> getConcurrencyStats(String platform, Long groupId) {
        MapSqlParameterSource params = opsDimensionParams(platform, groupId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", keyedMap(queryPlatformConcurrency(params), "platform"));
        payload.put("group", keyedMap(queryGroupConcurrency(params), "group_id"));
        payload.put("account", keyedMap(queryAccountConcurrency(params), "account_id"));
        return payload;
    }

    public Map<String, Object> getUserConcurrencyStats() {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select id, email, username, coalesce(concurrency, 0) as max_capacity
                from users
                where deleted_at is null and status = 'active'
                order by id asc
                """, new MapSqlParameterSource(), (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            long maxCapacity = rs.getLong("max_capacity");
            item.put("user_id", rs.getLong("id"));
            item.put("user_email", defaultString(rs.getString("email")));
            item.put("username", defaultString(rs.getString("username")));
            item.put("current_in_use", 0L);
            item.put("max_capacity", maxCapacity);
            item.put("waiting_in_queue", 0L);
            item.put("load_percentage", 0.0d);
            return item;
        });
        Map<String, Object> users = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            users.put(String.valueOf(row.get("user_id")), row);
        }
        return users;
    }

    public Map<String, Object> getAccountAvailabilityStats(String platform, Long groupId) {
        MapSqlParameterSource params = opsDimensionParams(platform, groupId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", keyedMap(queryPlatformAvailability(params), "platform"));
        payload.put("group", keyedMap(queryGroupAvailability(params), "group_id"));
        payload.put("account", keyedMap(queryAccountAvailability(params), "account_id"));
        return payload;
    }

    public Map<String, Object> getWindowStats(Instant start, Instant end, String platform, Long groupId) {
        MapSqlParameterSource params = opsTimeParams(start, end, platform, groupId);
        Map<String, Object> usage = queryUsageCounts(params);
        Map<String, Object> errors = queryErrorCounts(params);

        long successCount = longValue(usage.get("success_count"));
        long errorCountTotal = longValue(errors.get("error_count_total"));
        long tokenConsumed = longValue(usage.get("token_consumed"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("start_time", toIsoString(timestamp(start)));
        payload.put("end_time", toIsoString(timestamp(end)));
        payload.put("success_count", successCount);
        payload.put("error_count_total", errorCountTotal);
        payload.put("request_count_total", successCount + errorCountTotal);
        payload.put("token_consumed", tokenConsumed);
        return payload;
    }

    public Map<String, Object> getRealtimeTrafficSummary(Instant start, Instant end, String platform, Long groupId) {
        MapSqlParameterSource params = opsTimeParams(start, end, platform, groupId);
        Map<String, Object> windowStats = getWindowStats(start, end, platform, groupId);
        Map<String, Object> currentStats = getWindowStats(end.minusSeconds(60), end, platform, groupId);
        Map<String, Object> peakRates = queryPeakRates(params);

        double seconds = Math.max(1.0d, end.getEpochSecond() - start.getEpochSecond());
        long requestCount = longValue(windowStats.get("request_count_total"));
        long tokenConsumed = longValue(windowStats.get("token_consumed"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("start_time", windowStats.get("start_time"));
        payload.put("end_time", windowStats.get("end_time"));
        payload.put("platform", blankToEmpty(platform));
        payload.put("group_id", groupId);
        payload.put("qps", rateSummary(
                roundTo1DP(longValue(currentStats.get("request_count_total")) / 60.0d),
                doubleValue(peakRates.get("qps_peak")),
                roundTo1DP(requestCount / seconds)
        ));
        payload.put("tps", rateSummary(
                roundTo1DP(longValue(currentStats.get("token_consumed")) / 60.0d),
                doubleValue(peakRates.get("tps_peak")),
                roundTo1DP(tokenConsumed / seconds)
        ));
        return payload;
    }

    public Map<String, Object> getDashboardOverview(Instant start, Instant end, String platform, Long groupId) {
        MapSqlParameterSource params = opsTimeParams(start, end, platform, groupId);
        Map<String, Object> usage = queryUsageCounts(params);
        Map<String, Object> errors = queryErrorCounts(params);
        Map<String, Object> latency = queryUsageLatency(params);
        Map<String, Object> currentStats = getWindowStats(end.minusSeconds(60), end, platform, groupId);
        Map<String, Object> peakRates = queryPeakRates(params);

        long successCount = longValue(usage.get("success_count"));
        long errorCountTotal = longValue(errors.get("error_count_total"));
        long businessLimitedCount = longValue(errors.get("business_limited_count"));
        long errorCountSla = longValue(errors.get("error_count_sla"));
        long upstreamExcl = longValue(errors.get("upstream_error_count_excl_429_529"));
        long upstream429 = longValue(errors.get("upstream_429_count"));
        long upstream529 = longValue(errors.get("upstream_529_count"));
        long tokenConsumed = longValue(usage.get("token_consumed"));

        long requestCountTotal = successCount + errorCountTotal;
        long requestCountSla = successCount + errorCountSla;
        double seconds = Math.max(1.0d, end.getEpochSecond() - start.getEpochSecond());
        double sla = roundTo4DP(safeDivide(successCount, requestCountSla));
        double errorRate = roundTo4DP(safeDivide(errorCountSla, requestCountSla));
        double upstreamErrorRate = roundTo4DP(safeDivide(upstreamExcl, requestCountSla));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("start_time", toIsoString(timestamp(start)));
        payload.put("end_time", toIsoString(timestamp(end)));
        payload.put("platform", blankToEmpty(platform));
        payload.put("group_id", groupId);
        payload.put("health_score", dashboardHealthScore(errorRate, upstreamErrorRate));
        payload.put("system_metrics", getLatestSystemMetrics());
        payload.put("job_heartbeats", listJobHeartbeats());
        payload.put("success_count", successCount);
        payload.put("error_count_total", errorCountTotal);
        payload.put("business_limited_count", businessLimitedCount);
        payload.put("error_count_sla", errorCountSla);
        payload.put("request_count_total", requestCountTotal);
        payload.put("request_count_sla", requestCountSla);
        payload.put("token_consumed", tokenConsumed);
        payload.put("sla", sla);
        payload.put("error_rate", errorRate);
        payload.put("upstream_error_rate", upstreamErrorRate);
        payload.put("upstream_error_count_excl_429_529", upstreamExcl);
        payload.put("upstream_429_count", upstream429);
        payload.put("upstream_529_count", upstream529);
        payload.put("qps", rateSummary(
                roundTo1DP(longValue(currentStats.get("request_count_total")) / 60.0d),
                doubleValue(peakRates.get("qps_peak")),
                roundTo1DP(requestCountTotal / seconds)
        ));
        payload.put("tps", rateSummary(
                roundTo1DP(longValue(currentStats.get("token_consumed")) / 60.0d),
                doubleValue(peakRates.get("tps_peak")),
                roundTo1DP(tokenConsumed / seconds)
        ));
        payload.put("duration", latency.get("duration"));
        payload.put("ttft", latency.get("ttft"));
        return payload;
    }

    public Map<String, Object> getThroughputTrend(
            Instant start,
            Instant end,
            String platform,
            Long groupId,
            int bucketSeconds
    ) {
        int safeBucket = safeBucketSeconds(bucketSeconds);
        MapSqlParameterSource params = opsTimeParams(start, end, platform, groupId);
        String sql = """
                with usage_buckets as (
                  select %s as bucket,
                         count(*) as success_count,
                         coalesce(sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0) as token_consumed
                  from usage_logs ul
                  left join groups g on g.id = ul.group_id
                  left join accounts a on a.id = ul.account_id
                  """ + usageOpsWhere() + """
                  group by 1
                ),
                error_buckets as (
                  select %s as bucket,
                         count(*) as error_count
                  from ops_error_logs e
                  """ + errorOpsWhere() + """
                    and coalesce(e.status_code, 0) >= 400
                  group by 1
                ),
                combined as (
                  select coalesce(u.bucket, e.bucket) as bucket,
                         coalesce(u.success_count, 0) + coalesce(e.error_count, 0) as request_count,
                         coalesce(u.token_consumed, 0) as token_consumed
                  from usage_buckets u
                  full outer join error_buckets e on e.bucket = u.bucket
                )
                select bucket, request_count, token_consumed
                from combined
                order by bucket asc
                """.formatted(usageBucketExpression(safeBucket), errorBucketExpression(safeBucket));
        List<Map<String, Object>> points = jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            long requestCount = rs.getLong("request_count");
            long tokenConsumed = rs.getLong("token_consumed");
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("bucket_start", toIsoString(rs.getTimestamp("bucket")));
            point.put("request_count", requestCount);
            point.put("token_consumed", tokenConsumed);
            point.put("switch_count", 0L);
            point.put("qps", roundTo1DP(requestCount / (double) safeBucket));
            point.put("tps", roundTo1DP(tokenConsumed / (double) safeBucket));
            return point;
        });

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bucket", bucketLabel(safeBucket));
        payload.put("points", fillThroughputBuckets(start, end, safeBucket, points));
        payload.put("by_platform", queryThroughputByPlatform(start, end, platform, groupId));
        payload.put("top_groups", queryThroughputTopGroups(start, end, platform, groupId));
        return payload;
    }

    public Map<String, Object> getLatencyHistogram(Instant start, Instant end, String platform, Long groupId) {
        MapSqlParameterSource params = opsTimeParams(start, end, platform, groupId);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select
                  case
                    when ul.duration_ms < 100 then '0-100ms'
                    when ul.duration_ms < 200 then '100-200ms'
                    when ul.duration_ms < 500 then '200-500ms'
                    when ul.duration_ms < 1000 then '500-1000ms'
                    when ul.duration_ms < 2000 then '1000-2000ms'
                    else '2000ms+'
                  end as range,
                  count(*) as count
                from usage_logs ul
                left join groups g on g.id = ul.group_id
                left join accounts a on a.id = ul.account_id
                """ + usageOpsWhere() + """
                  and ul.duration_ms is not null
                group by 1
                """, params, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("range", defaultString(rs.getString("range")));
            row.put("count", rs.getLong("count"));
            return row;
        });
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(String.valueOf(row.get("range")), longValue(row.get("count")));
        }
        List<Map<String, Object>> buckets = new ArrayList<>();
        long total = 0L;
        for (String label : List.of("0-100ms", "100-200ms", "200-500ms", "500-1000ms", "1000-2000ms", "2000ms+")) {
            long count = counts.getOrDefault(label, 0L);
            total += count;
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("range", label);
            bucket.put("count", count);
            buckets.add(bucket);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("start_time", toIsoString(timestamp(start)));
        payload.put("end_time", toIsoString(timestamp(end)));
        payload.put("platform", blankToEmpty(platform));
        payload.put("group_id", groupId);
        payload.put("total_requests", total);
        payload.put("buckets", buckets);
        return payload;
    }

    public Map<String, Object> getErrorTrend(
            Instant start,
            Instant end,
            String platform,
            Long groupId,
            int bucketSeconds
    ) {
        int safeBucket = safeBucketSeconds(bucketSeconds);
        MapSqlParameterSource params = opsTimeParams(start, end, platform, groupId);
        String sql = """
                select %s as bucket,
                       count(*) filter (where coalesce(e.status_code, 0) >= 400) as error_count_total,
                       count(*) filter (where coalesce(e.status_code, 0) >= 400 and e.is_business_limited) as business_limited_count,
                       count(*) filter (where coalesce(e.status_code, 0) >= 400 and not e.is_business_limited) as error_count_sla,
                       count(*) filter (where e.error_owner = 'provider' and not e.is_business_limited and coalesce(e.upstream_status_code, e.status_code, 0) not in (429, 529)) as upstream_error_count_excl_429_529,
                       count(*) filter (where e.error_owner = 'provider' and not e.is_business_limited and coalesce(e.upstream_status_code, e.status_code, 0) = 429) as upstream_429_count,
                       count(*) filter (where e.error_owner = 'provider' and not e.is_business_limited and coalesce(e.upstream_status_code, e.status_code, 0) = 529) as upstream_529_count
                from ops_error_logs e
                """ + errorOpsWhere() + """
                group by 1
                order by 1 asc
                """.formatted(errorBucketExpression(safeBucket));
        List<Map<String, Object>> points = jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("bucket_start", toIsoString(rs.getTimestamp("bucket")));
            point.put("error_count_total", rs.getLong("error_count_total"));
            point.put("business_limited_count", rs.getLong("business_limited_count"));
            point.put("error_count_sla", rs.getLong("error_count_sla"));
            point.put("upstream_error_count_excl_429_529", rs.getLong("upstream_error_count_excl_429_529"));
            point.put("upstream_429_count", rs.getLong("upstream_429_count"));
            point.put("upstream_529_count", rs.getLong("upstream_529_count"));
            return point;
        });

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bucket", bucketLabel(safeBucket));
        payload.put("points", fillErrorTrendBuckets(start, end, safeBucket, points));
        return payload;
    }

    public Map<String, Object> getErrorDistribution(Instant start, Instant end, String platform, Long groupId) {
        MapSqlParameterSource params = opsTimeParams(start, end, platform, groupId);
        List<Map<String, Object>> items = jdbcTemplate.query("""
                select coalesce(e.upstream_status_code, e.status_code, 0) as status_code,
                       count(*) as total,
                       count(*) filter (where not e.is_business_limited) as sla,
                       count(*) filter (where e.is_business_limited) as business_limited
                from ops_error_logs e
                """ + errorOpsWhere() + """
                  and coalesce(e.status_code, 0) >= 400
                group by 1
                order by total desc
                limit 20
                """, params, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status_code", rs.getInt("status_code"));
            item.put("total", rs.getLong("total"));
            item.put("sla", rs.getLong("sla"));
            item.put("business_limited", rs.getLong("business_limited"));
            return item;
        });
        long total = items.stream().mapToLong(item -> longValue(item.get("total"))).sum();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", total);
        payload.put("items", items);
        return payload;
    }

    public Map<String, Object> getOpenAiTokenStats(
            Instant start,
            Instant end,
            String timeRange,
            String platform,
            Long groupId,
            int page,
            int pageSize,
            Integer topN
    ) {
        MapSqlParameterSource params = opsTimeParams(start, end, platform, groupId);
        String baseCte = """
                with stats as (
                  select ul.model as model,
                         count(*)::bigint as request_count,
                         round(avg(case when ul.duration_ms > 0 and ul.output_tokens > 0 then ul.output_tokens * 1000.0 / ul.duration_ms end)::numeric, 2)::float8 as avg_tokens_per_sec,
                         round(avg(ul.first_token_ms)::numeric, 2)::float8 as avg_first_token_ms,
                         coalesce(sum(ul.output_tokens), 0)::bigint as total_output_tokens,
                         coalesce(round(avg(ul.duration_ms)::numeric, 0), 0)::bigint as avg_duration_ms,
                         count(*) filter (where ul.first_token_ms is not null)::bigint as requests_with_first_token
                  from usage_logs ul
                  left join groups g on g.id = ul.group_id
                  left join accounts a on a.id = ul.account_id
                  """ + usageOpsWhere() + """
                    and ul.model like 'gpt%'
                  group by ul.model
                )
                """;
        Long total = jdbcTemplate.queryForObject(baseCte + "select count(*) from stats", params, Long.class);

        String sql = baseCte + """
                select model, request_count, avg_tokens_per_sec, avg_first_token_ms,
                       total_output_tokens, avg_duration_ms, requests_with_first_token
                from stats
                order by request_count desc, model asc
                """;
        MapSqlParameterSource queryParams = new MapSqlParameterSource(params.getValues());
        if (topN != null && topN > 0) {
            queryParams.addValue("limit", Math.min(topN, 100));
            sql += " limit :limit";
        } else {
            queryParams.addValue("limit", Math.max(1, Math.min(pageSize, 100)));
            queryParams.addValue("offset", (Math.max(page, 1) - 1) * Math.max(1, Math.min(pageSize, 100)));
            sql += " limit :limit offset :offset";
        }
        List<Map<String, Object>> items = jdbcTemplate.query(sql, queryParams, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", defaultString(rs.getString("model")));
            item.put("request_count", rs.getLong("request_count"));
            item.put("avg_tokens_per_sec", rs.getObject("avg_tokens_per_sec"));
            item.put("avg_first_token_ms", rs.getObject("avg_first_token_ms"));
            item.put("total_output_tokens", rs.getLong("total_output_tokens"));
            item.put("avg_duration_ms", rs.getLong("avg_duration_ms"));
            item.put("requests_with_first_token", rs.getLong("requests_with_first_token"));
            return item;
        });

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("time_range", blankToEmpty(timeRange));
        payload.put("start_time", toIsoString(timestamp(start)));
        payload.put("end_time", toIsoString(timestamp(end)));
        payload.put("platform", blankToEmpty(platform));
        payload.put("group_id", groupId);
        payload.put("items", items);
        payload.put("total", total == null ? 0L : total);
        if (topN != null && topN > 0) {
            payload.put("page", null);
            payload.put("page_size", null);
            payload.put("top_n", Math.min(topN, 100));
        } else {
            payload.put("page", Math.max(page, 1));
            payload.put("page_size", Math.max(1, Math.min(pageSize, 100)));
            payload.put("top_n", null);
        }
        return payload;
    }

    public List<Map<String, Object>> listAlertRules() {
        return jdbcTemplate.query("""
                select id, name, description, enabled, severity, metric_type, operator, threshold,
                       window_minutes, sustained_minutes, cooldown_minutes, notify_email,
                       filters::text as filters_json, last_triggered_at, created_at, updated_at
                from ops_alert_rules
                order by id asc
                """, new MapSqlParameterSource(), (rs, rowNum) -> mapAlertRule(rs));
    }

    public Map<String, Object> createAlertRule(Map<String, Object> input) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into ops_alert_rules (
                    name, description, enabled, severity, metric_type, operator, threshold,
                    window_minutes, sustained_minutes, cooldown_minutes, notify_email, filters,
                    created_at, updated_at
                ) values (
                    :name, :description, :enabled, :severity, :metricType, :operator, :threshold,
                    :windowMinutes, :sustainedMinutes, :cooldownMinutes, :notifyEmail, cast(:filters as jsonb),
                    now(), now()
                )
                returning id
                """, alertRuleParams(input), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to create alert rule");
        }
        return getAlertRule(key.longValue());
    }

    public Map<String, Object> getAlertRule(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select id, name, description, enabled, severity, metric_type, operator, threshold,
                       window_minutes, sustained_minutes, cooldown_minutes, notify_email,
                       filters::text as filters_json, last_triggered_at, created_at, updated_at
                from ops_alert_rules
                where id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapAlertRule(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> updateAlertRule(long id, Map<String, Object> input) {
        jdbcTemplate.update("""
                update ops_alert_rules
                set name = :name,
                    description = :description,
                    enabled = :enabled,
                    severity = :severity,
                    metric_type = :metricType,
                    operator = :operator,
                    threshold = :threshold,
                    window_minutes = :windowMinutes,
                    sustained_minutes = :sustainedMinutes,
                    cooldown_minutes = :cooldownMinutes,
                    notify_email = :notifyEmail,
                    filters = cast(:filters as jsonb),
                    updated_at = now()
                where id = :id
                """, alertRuleParams(input).addValue("id", id));
        return getAlertRule(id);
    }

    public boolean deleteAlertRule(long id) {
        return jdbcTemplate.update("""
                delete from ops_alert_rules
                where id = :id
                """, new MapSqlParameterSource("id", id)) > 0;
    }

    public List<Map<String, Object>> listAlertEvents(
            Integer limit,
            String status,
            String severity,
            Boolean emailSent,
            String platform,
            Long groupId,
            Instant startTime,
            Instant endTime,
            Instant beforeFiredAt,
            Long beforeId
    ) {
        int resolvedLimit = limit == null || limit < 1 ? 50 : Math.min(limit, 200);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", resolvedLimit)
                .addValue("status", blankToNull(status))
                .addValue("severity", blankToNull(severity))
                .addValue("emailSent", emailSent)
                .addValue("platform", blankToNull(platform))
                .addValue("groupId", groupId)
                .addValue("startTime", timestamp(startTime))
                .addValue("endTime", timestamp(endTime))
                .addValue("beforeFiredAt", timestamp(beforeFiredAt))
                .addValue("beforeId", beforeId);
        return jdbcTemplate.query("""
                select e.id, e.rule_id, e.severity, e.status, e.title, e.description,
                       e.metric_value, e.threshold_value, e.dimensions::text as dimensions_json,
                       e.fired_at, e.resolved_at, e.email_sent, e.created_at,
                       coalesce((e.dimensions ->> 'platform'), '') as dimension_platform,
                       nullif(e.dimensions ->> 'group_id', '')::bigint as dimension_group_id
                from ops_alert_events e
                where (:status is null or e.status = :status)
                  and (:severity is null or e.severity = :severity)
                  and (:emailSent is null or e.email_sent = :emailSent)
                  and (:platform is null or coalesce((e.dimensions ->> 'platform'), '') = :platform)
                  and (:groupId is null or nullif(e.dimensions ->> 'group_id', '')::bigint = :groupId)
                  and (:startTime is null or e.fired_at >= :startTime)
                  and (:endTime is null or e.fired_at <= :endTime)
                  and (
                        :beforeFiredAt is null
                        or e.fired_at < :beforeFiredAt
                        or (e.fired_at = :beforeFiredAt and (:beforeId is null or e.id < :beforeId))
                  )
                order by e.fired_at desc, e.id desc
                limit :limit
                """, params, (rs, rowNum) -> mapAlertEvent(rs));
    }

    public Map<String, Object> getAlertEvent(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select e.id, e.rule_id, e.severity, e.status, e.title, e.description,
                       e.metric_value, e.threshold_value, e.dimensions::text as dimensions_json,
                       e.fired_at, e.resolved_at, e.email_sent, e.created_at
                from ops_alert_events e
                where e.id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapAlertEvent(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean updateAlertEventStatus(long id, String status, Long operatorUserId) {
        return jdbcTemplate.update("""
                update ops_alert_events
                set status = :status,
                    resolved_at = case when :status in ('resolved', 'manual_resolved') then now() else resolved_at end
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("operatorUserId", operatorUserId)) > 0;
    }

    public Map<String, Object> createAlertSilence(
            long ruleId,
            String platform,
            Long groupId,
            String region,
            Instant until,
            String reason,
            Long createdBy
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into ops_alert_silences (
                    rule_id, platform, group_id, region, until, reason, created_by, created_at
                ) values (
                    :ruleId, :platform, :groupId, :region, :until, :reason, :createdBy, now()
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("ruleId", ruleId)
                .addValue("platform", blankToEmpty(platform))
                .addValue("groupId", groupId)
                .addValue("region", blankToNull(region))
                .addValue("until", timestamp(until))
                .addValue("reason", blankToEmpty(reason))
                .addValue("createdBy", createdBy), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("rule_id", ruleId);
        payload.put("platform", blankToEmpty(platform));
        payload.put("group_id", groupId);
        payload.put("region", blankToNull(region));
        payload.put("until", toIsoString(timestamp(until)));
        payload.put("reason", blankToEmpty(reason));
        payload.put("created_by", createdBy);
        return payload;
    }

    public PageResponse<Map<String, Object>> listSystemLogs(Map<String, Object> filter, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = (safePage - 1) * safePageSize;
        String where = systemLogWhere();
        MapSqlParameterSource params = systemLogParams(filter)
                .addValue("limit", safePageSize)
                .addValue("offset", offset);
        Long total = jdbcTemplate.queryForObject("select count(*) from ops_system_logs l " + where, params, Long.class);
        List<Map<String, Object>> items = jdbcTemplate.query("""
                select l.id, l.created_at, l.level, l.component, l.message, l.request_id, l.client_request_id,
                       l.user_id, l.account_id, l.platform, l.model, l.extra::text as extra_json
                from ops_system_logs l
                """ + where + """
                order by l.created_at desc, l.id desc
                limit :limit offset :offset
                """, params, (rs, rowNum) -> mapSystemLog(rs));
        return new PageResponse<>(items, total == null ? 0L : total, safePage, safePageSize);
    }

    @Transactional
    public long cleanupSystemLogs(Map<String, Object> filter, Long operatorId) {
        MapSqlParameterSource params = systemLogParams(filter);
        Long deleted = jdbcTemplate.queryForObject("""
                with deleted as (
                    delete from ops_system_logs l
                    """ + systemLogWhere() + """
                    returning 1
                )
                select count(*) from deleted
                """, params, Long.class);
        long deletedRows = deleted == null ? 0L : deleted;
        jdbcTemplate.update("""
                insert into ops_system_log_cleanup_audits (operator_id, conditions, deleted_rows, created_at)
                values (:operatorId, cast(:conditions as jsonb), :deletedRows, now())
                """, new MapSqlParameterSource()
                .addValue("operatorId", operatorId == null ? 0L : operatorId)
                .addValue("conditions", jsonHelper.writeJson(filter == null ? Map.of() : filter))
                .addValue("deletedRows", deletedRows));
        return deletedRows;
    }

    public PageResponse<Map<String, Object>> listErrorLogs(Map<String, Object> filter, int page, int pageSize, boolean upstreamOnly) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 500));
        int offset = (safePage - 1) * safePageSize;
        String where = errorLogWhere(upstreamOnly);
        MapSqlParameterSource params = errorLogParams(filter, upstreamOnly)
                .addValue("limit", safePageSize)
                .addValue("offset", offset);
        Long total = jdbcTemplate.queryForObject("select count(*) from ops_error_logs e " + where, params, Long.class);
        List<Map<String, Object>> items = jdbcTemplate.query("""
                select e.id, e.created_at, e.error_phase, e.error_type, e.error_owner, e.error_source,
                       e.severity, e.status_code, e.platform, e.model, e.is_retryable, e.retry_count,
                       e.resolved, e.resolved_at, e.resolved_by_user_id, e.resolved_retry_id,
                       e.client_request_id, e.request_id, e.error_message, e.user_id, u.email as user_email,
                       e.api_key_id, e.account_id, a.name as account_name, e.group_id, g.name as group_name,
                       cast(e.client_ip as text) as client_ip, e.request_path, e.stream,
                       e.inbound_endpoint, e.upstream_endpoint, e.requested_model, e.upstream_model, e.request_type
                from ops_error_logs e
                left join users u on u.id = e.user_id
                left join accounts a on a.id = e.account_id
                left join groups g on g.id = e.group_id
                """ + where + """
                order by e.created_at desc, e.id desc
                limit :limit offset :offset
                """, params, (rs, rowNum) -> mapErrorLog(rs));
        return new PageResponse<>(items, total == null ? 0L : total, safePage, safePageSize);
    }

    public PageResponse<Map<String, Object>> listRequestDetails(Map<String, Object> filter, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = (safePage - 1) * safePageSize;
        String where = requestDetailsWhere(filter);
        String sort = requestDetailsSort(filter);
        MapSqlParameterSource params = requestDetailsParams(filter)
                .addValue("limit", safePageSize)
                .addValue("offset", offset);
        String cte = """
                with combined as (
                  select
                    'success'::text as kind,
                    ul.created_at as created_at,
                    ul.request_id as request_id,
                    coalesce(nullif(g.platform, ''), nullif(a.platform, ''), '') as platform,
                    ul.model as model,
                    ul.duration_ms as duration_ms,
                    null::int as status_code,
                    null::bigint as error_id,
                    null::text as phase,
                    null::text as severity,
                    null::text as message,
                    ul.user_id as user_id,
                    ul.api_key_id as api_key_id,
                    ul.account_id as account_id,
                    ul.group_id as group_id,
                    ul.stream as stream
                  from usage_logs ul
                  left join groups g on g.id = ul.group_id
                  left join accounts a on a.id = ul.account_id
                  where ul.created_at >= :startTime and ul.created_at < :endTime

                  union all

                  select
                    'error'::text as kind,
                    o.created_at as created_at,
                    coalesce(nullif(o.request_id,''), nullif(o.client_request_id,''), '') as request_id,
                    coalesce(nullif(o.platform, ''), nullif(g.platform, ''), nullif(a.platform, ''), '') as platform,
                    o.model as model,
                    o.duration_ms as duration_ms,
                    o.status_code as status_code,
                    o.id as error_id,
                    o.error_phase as phase,
                    o.severity as severity,
                    o.error_message as message,
                    o.user_id as user_id,
                    o.api_key_id as api_key_id,
                    o.account_id as account_id,
                    o.group_id as group_id,
                    o.stream as stream
                  from ops_error_logs o
                  left join groups g on g.id = o.group_id
                  left join accounts a on a.id = o.account_id
                  where o.created_at >= :startTime and o.created_at < :endTime
                    and coalesce(o.status_code, 0) >= 400
                )
                """;
        Long total = jdbcTemplate.queryForObject(
                cte + " select count(*) from combined " + where,
                params,
                Long.class
        );
        List<Map<String, Object>> items = jdbcTemplate.query(
                cte + """
                 select
                   kind,
                   created_at,
                   request_id,
                   platform,
                   model,
                   duration_ms,
                   status_code,
                   error_id,
                   phase,
                   severity,
                   message,
                   user_id,
                   api_key_id,
                   account_id,
                   group_id,
                   stream
                 from combined
                """ + where + " " + sort + """
                 limit :limit offset :offset
                """,
                params,
                (rs, rowNum) -> mapRequestDetail(rs)
        );
        return new PageResponse<>(items, total == null ? 0L : total, safePage, safePageSize);
    }

    public Map<String, Object> getErrorLog(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select e.id, e.created_at, e.error_phase, e.error_type, e.error_owner, e.error_source,
                       e.severity, e.status_code, e.platform, e.model, e.is_retryable, e.retry_count,
                       e.resolved, e.resolved_at, e.resolved_by_user_id, e.resolved_retry_id,
                       e.client_request_id, e.request_id, e.error_message, e.user_id, u.email as user_email,
                       e.api_key_id, e.account_id, a.name as account_name, e.group_id, g.name as group_name,
                       cast(e.client_ip as text) as client_ip, e.request_path, e.stream,
                       e.inbound_endpoint, e.upstream_endpoint, e.requested_model, e.upstream_model, e.request_type,
                       e.error_body, e.user_agent, e.upstream_status_code, e.upstream_error_message,
                       e.upstream_error_detail, e.upstream_errors::text as upstream_errors_json,
                       e.auth_latency_ms, e.routing_latency_ms, e.upstream_latency_ms, e.response_latency_ms,
                       e.time_to_first_token_ms, e.request_body::text as request_body_json,
                       e.request_headers::text as request_headers_json,
                       e.request_body_truncated, e.request_body_bytes, e.is_business_limited
                from ops_error_logs e
                left join users u on u.id = e.user_id
                left join accounts a on a.id = e.account_id
                left join groups g on g.id = e.group_id
                where e.id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapErrorDetail(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Optional<Map<String, Object>> findErrorLog(long id) {
        return Optional.ofNullable(getErrorLog(id));
    }

    public PageResponse<Map<String, Object>> listRequestErrorUpstreamErrors(long requestErrorId, Map<String, Object> filter, int page, int pageSize) {
        Map<String, Object> detail = getErrorLog(requestErrorId);
        if (detail == null) {
            return new PageResponse<>(List.of(), 0L, Math.max(page, 1), Math.max(pageSize, 1));
        }
        String requestId = blankToEmpty((String) detail.get("request_id"));
        String clientRequestId = blankToEmpty((String) detail.get("client_request_id"));
        if (requestId.isEmpty() && clientRequestId.isEmpty()) {
            return new PageResponse<>(List.of(), 0L, Math.max(page, 1), Math.max(pageSize, 1));
        }
        Map<String, Object> merged = new LinkedHashMap<>(filter == null ? Map.of() : filter);
        merged.put("request_id", requestId);
        merged.put("client_request_id", clientRequestId);
        merged.put("phase", "upstream");
        merged.put("error_owner", "provider");
        return listErrorLogs(merged, page, pageSize, true);
    }

    public boolean updateErrorResolved(long id, boolean resolved, Long operatorId) {
        return jdbcTemplate.update("""
                update ops_error_logs
                set resolved = :resolved,
                    resolved_at = case when :resolved then now() else null end,
                    resolved_by_user_id = case when :resolved then :operatorId else null end
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("resolved", resolved)
                .addValue("operatorId", operatorId)) > 0;
    }

    public List<Map<String, Object>> listRetryAttempts(long errorId, int limit) {
        int safeLimit = limit < 1 ? 50 : Math.min(limit, 200);
        return jdbcTemplate.query("""
                select id, created_at, requested_by_user_id, source_error_id, mode, pinned_account_id,
                       status, started_at, finished_at, duration_ms, success, http_status_code,
                       upstream_request_id, used_account_id, response_preview, response_truncated,
                       result_request_id, result_error_id, error_message
                from ops_retry_attempts
                where source_error_id = :errorId
                order by created_at desc, id desc
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("errorId", errorId)
                .addValue("limit", safeLimit), (rs, rowNum) -> mapRetryAttempt(rs));
    }

    public Optional<Map<String, Object>> findLatestRetryAttempt(long errorId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select id, created_at, requested_by_user_id, source_error_id, mode, pinned_account_id,
                       status, started_at, finished_at, duration_ms, success, http_status_code,
                       upstream_request_id, used_account_id, response_preview, response_truncated,
                       result_request_id, result_error_id, error_message
                from ops_retry_attempts
                where source_error_id = :errorId
                order by created_at desc, id desc
                limit 1
                """, new MapSqlParameterSource("errorId", errorId), (rs, rowNum) -> mapRetryAttempt(rs));
        return rows.stream().findFirst();
    }

    public Map<String, Object> insertRetryAttemptRunning(long errorId, long requestedByUserId, String mode, Long pinnedAccountId, Instant startedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into ops_retry_attempts (
                    requested_by_user_id, source_error_id, mode, pinned_account_id,
                    status, started_at, finished_at, duration_ms, success, http_status_code,
                    upstream_request_id, used_account_id, response_preview, response_truncated,
                    result_request_id, result_error_id, error_message, created_at
                ) values (
                    :requestedByUserId, :sourceErrorId, :mode, :pinnedAccountId,
                    'running', :startedAt, null, null, null, null,
                    '', null, '', false,
                    null, null, null, :startedAt
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("requestedByUserId", requestedByUserId)
                .addValue("sourceErrorId", errorId)
                .addValue("mode", blankToEmpty(mode))
                .addValue("pinnedAccountId", pinnedAccountId)
                .addValue("startedAt", timestamp(startedAt)), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select id, created_at, requested_by_user_id, source_error_id, mode, pinned_account_id,
                       status, started_at, finished_at, duration_ms, success, http_status_code,
                       upstream_request_id, used_account_id, response_preview, response_truncated,
                       result_request_id, result_error_id, error_message
                from ops_retry_attempts
                where id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapRetryAttempt(rs));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public Map<String, Object> createRetryAttempt(long errorId, long requestedByUserId, String mode, Long pinnedAccountId, String errorMessage) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into ops_retry_attempts (
                    requested_by_user_id, source_error_id, mode, pinned_account_id,
                    status, started_at, finished_at, duration_ms, success, http_status_code,
                    upstream_request_id, used_account_id, response_preview, response_truncated,
                    result_request_id, result_error_id, error_message, created_at
                ) values (
                    :requestedByUserId, :sourceErrorId, :mode, :pinnedAccountId,
                    'failed', now(), now(), 0, false, 501,
                    '', null, '', false,
                    null, null, :errorMessage, now()
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("requestedByUserId", requestedByUserId)
                .addValue("sourceErrorId", errorId)
                .addValue("mode", blankToEmpty(mode))
                .addValue("pinnedAccountId", pinnedAccountId)
                .addValue("errorMessage", blankToEmpty(errorMessage)), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select id, created_at, requested_by_user_id, source_error_id, mode, pinned_account_id,
                       status, started_at, finished_at, duration_ms, success, http_status_code,
                       upstream_request_id, used_account_id, response_preview, response_truncated,
                       result_request_id, result_error_id, error_message
                from ops_retry_attempts
                where id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapRetryAttempt(rs));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public void updateRetryAttempt(
            long attemptId,
            String status,
            Instant finishedAt,
            long durationMs,
            boolean success,
            Integer httpStatusCode,
            String upstreamRequestId,
            Long usedAccountId,
            String responsePreview,
            boolean responseTruncated,
            String errorMessage
    ) {
        jdbcTemplate.update("""
                update ops_retry_attempts
                set status = :status,
                    finished_at = :finishedAt,
                    duration_ms = :durationMs,
                    success = :success,
                    http_status_code = :httpStatusCode,
                    upstream_request_id = :upstreamRequestId,
                    used_account_id = :usedAccountId,
                    response_preview = :responsePreview,
                    response_truncated = :responseTruncated,
                    error_message = :errorMessage
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", attemptId)
                .addValue("status", blankToEmpty(status))
                .addValue("finishedAt", timestamp(finishedAt))
                .addValue("durationMs", durationMs)
                .addValue("success", success)
                .addValue("httpStatusCode", httpStatusCode)
                .addValue("upstreamRequestId", blankToEmpty(upstreamRequestId))
                .addValue("usedAccountId", usedAccountId)
                .addValue("responsePreview", responsePreview == null ? "" : responsePreview)
                .addValue("responseTruncated", responseTruncated)
                .addValue("errorMessage", blankToNull(errorMessage)));
    }

    public void markErrorResolvedByRetry(long errorId, long operatorId, long retryAttemptId, Instant resolvedAt) {
        jdbcTemplate.update("""
                update ops_error_logs
                set resolved = true,
                    resolved_at = :resolvedAt,
                    resolved_by_user_id = :operatorId,
                    resolved_retry_id = :retryAttemptId
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", errorId)
                .addValue("resolvedAt", timestamp(resolvedAt))
                .addValue("operatorId", operatorId)
                .addValue("retryAttemptId", retryAttemptId));
    }

    private MapSqlParameterSource opsDimensionParams(String platform, Long groupId) {
        return new MapSqlParameterSource()
                .addValue("platform", blankToNull(platform))
                .addValue("groupId", groupId == null || groupId <= 0 ? null : groupId);
    }

    private MapSqlParameterSource opsTimeParams(Instant start, Instant end, String platform, Long groupId) {
        return opsDimensionParams(platform, groupId)
                .addValue("startTime", timestamp(start))
                .addValue("endTime", timestamp(end));
    }

    private String usageOpsWhere() {
        return """
                where ul.created_at >= :startTime and ul.created_at < :endTime
                  and (:groupId is null or ul.group_id = :groupId)
                  and (:platform is null or coalesce(nullif(g.platform, ''), a.platform) = :platform)
                """;
    }

    private String errorOpsWhere() {
        return """
                where e.created_at >= :startTime and e.created_at < :endTime
                  and e.is_count_tokens = false
                  and (:groupId is null or e.group_id = :groupId)
                  and (:platform is null or e.platform = :platform)
                """;
    }

    private Map<String, Object> queryUsageCounts(MapSqlParameterSource params) {
        return jdbcTemplate.queryForObject("""
                select count(*)::bigint as success_count,
                       coalesce(sum(ul.input_tokens + ul.output_tokens + ul.cache_creation_tokens + ul.cache_read_tokens), 0)::bigint as token_consumed
                from usage_logs ul
                left join groups g on g.id = ul.group_id
                left join accounts a on a.id = ul.account_id
                """ + usageOpsWhere(), params, (rs, rowNum) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success_count", rs.getLong("success_count"));
            payload.put("token_consumed", rs.getLong("token_consumed"));
            return payload;
        });
    }

    private Map<String, Object> queryUsageLatency(MapSqlParameterSource params) {
        Map<String, Object> row = jdbcTemplate.queryForObject("""
                select percentile_cont(0.50) within group (order by ul.duration_ms) filter (where ul.duration_ms is not null) as duration_p50,
                       percentile_cont(0.90) within group (order by ul.duration_ms) filter (where ul.duration_ms is not null) as duration_p90,
                       percentile_cont(0.95) within group (order by ul.duration_ms) filter (where ul.duration_ms is not null) as duration_p95,
                       percentile_cont(0.99) within group (order by ul.duration_ms) filter (where ul.duration_ms is not null) as duration_p99,
                       avg(ul.duration_ms) filter (where ul.duration_ms is not null) as duration_avg,
                       max(ul.duration_ms) as duration_max,
                       percentile_cont(0.50) within group (order by ul.first_token_ms) filter (where ul.first_token_ms is not null) as ttft_p50,
                       percentile_cont(0.90) within group (order by ul.first_token_ms) filter (where ul.first_token_ms is not null) as ttft_p90,
                       percentile_cont(0.95) within group (order by ul.first_token_ms) filter (where ul.first_token_ms is not null) as ttft_p95,
                       percentile_cont(0.99) within group (order by ul.first_token_ms) filter (where ul.first_token_ms is not null) as ttft_p99,
                       avg(ul.first_token_ms) filter (where ul.first_token_ms is not null) as ttft_avg,
                       max(ul.first_token_ms) as ttft_max
                from usage_logs ul
                left join groups g on g.id = ul.group_id
                left join accounts a on a.id = ul.account_id
                """ + usageOpsWhere(), params, (rs, rowNum) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("duration", percentiles(
                    rs.getObject("duration_p50", Double.class),
                    rs.getObject("duration_p90", Double.class),
                    rs.getObject("duration_p95", Double.class),
                    rs.getObject("duration_p99", Double.class),
                    rs.getObject("duration_avg", Double.class),
                    rs.getObject("duration_max", Integer.class)
            ));
            payload.put("ttft", percentiles(
                    rs.getObject("ttft_p50", Double.class),
                    rs.getObject("ttft_p90", Double.class),
                    rs.getObject("ttft_p95", Double.class),
                    rs.getObject("ttft_p99", Double.class),
                    rs.getObject("ttft_avg", Double.class),
                    rs.getObject("ttft_max", Integer.class)
            ));
            return payload;
        });
        return row == null ? Map.of("duration", percentiles(null, null, null, null, null, null),
                "ttft", percentiles(null, null, null, null, null, null)) : row;
    }

    private Map<String, Object> queryErrorCounts(MapSqlParameterSource params) {
        return jdbcTemplate.queryForObject("""
                select count(*) filter (where coalesce(e.status_code, 0) >= 400)::bigint as error_count_total,
                       count(*) filter (where coalesce(e.status_code, 0) >= 400 and e.is_business_limited)::bigint as business_limited_count,
                       count(*) filter (where coalesce(e.status_code, 0) >= 400 and not e.is_business_limited)::bigint as error_count_sla,
                       count(*) filter (where e.error_owner = 'provider' and not e.is_business_limited and coalesce(e.upstream_status_code, e.status_code, 0) not in (429, 529))::bigint as upstream_error_count_excl_429_529,
                       count(*) filter (where e.error_owner = 'provider' and not e.is_business_limited and coalesce(e.upstream_status_code, e.status_code, 0) = 429)::bigint as upstream_429_count,
                       count(*) filter (where e.error_owner = 'provider' and not e.is_business_limited and coalesce(e.upstream_status_code, e.status_code, 0) = 529)::bigint as upstream_529_count
                from ops_error_logs e
                """ + errorOpsWhere(), params, (rs, rowNum) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("error_count_total", rs.getLong("error_count_total"));
            payload.put("business_limited_count", rs.getLong("business_limited_count"));
            payload.put("error_count_sla", rs.getLong("error_count_sla"));
            payload.put("upstream_error_count_excl_429_529", rs.getLong("upstream_error_count_excl_429_529"));
            payload.put("upstream_429_count", rs.getLong("upstream_429_count"));
            payload.put("upstream_529_count", rs.getLong("upstream_529_count"));
            return payload;
        });
    }

    private Map<String, Object> queryPeakRates(MapSqlParameterSource params) {
        return jdbcTemplate.queryForObject("""
                with usage_buckets as (
                  select date_trunc('minute', ul.created_at) as bucket,
                         count(*) as req_cnt,
                         coalesce(sum(ul.input_tokens + ul.output_tokens + ul.cache_creation_tokens + ul.cache_read_tokens), 0) as token_cnt
                  from usage_logs ul
                  left join groups g on g.id = ul.group_id
                  left join accounts a on a.id = ul.account_id
                  """ + usageOpsWhere() + """
                  group by 1
                ),
                error_buckets as (
                  select date_trunc('minute', e.created_at) as bucket,
                         count(*) as err_cnt
                  from ops_error_logs e
                  """ + errorOpsWhere() + """
                    and coalesce(e.status_code, 0) >= 400
                  group by 1
                ),
                combined as (
                  select coalesce(u.bucket, e.bucket) as bucket,
                         coalesce(u.req_cnt, 0) + coalesce(e.err_cnt, 0) as total_req,
                         coalesce(u.token_cnt, 0) as total_tokens
                  from usage_buckets u
                  full outer join error_buckets e on e.bucket = u.bucket
                )
                select coalesce(max(total_req), 0)::bigint as max_req_per_min,
                       coalesce(max(total_tokens), 0)::bigint as max_tokens_per_min
                from combined
                """, params, (rs, rowNum) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("qps_peak", roundTo1DP(rs.getLong("max_req_per_min") / 60.0d));
            payload.put("tps_peak", roundTo1DP(rs.getLong("max_tokens_per_min") / 60.0d));
            return payload;
        });
    }

    private List<Map<String, Object>> queryPlatformConcurrency(MapSqlParameterSource params) {
        return jdbcTemplate.query("""
                select a.platform,
                       count(*)::bigint as account_count,
                       coalesce(sum(a.concurrency), 0)::bigint as max_capacity
                from accounts a
                left join account_groups ag on ag.account_id = a.id
                where a.deleted_at is null
                  and (:platform is null or a.platform = :platform)
                  and (:groupId is null or ag.group_id = :groupId)
                group by a.platform
                order by a.platform asc
                """, params, (rs, rowNum) -> concurrencyRow(
                defaultString(rs.getString("platform")),
                rs.getLong("max_capacity"),
                Map.of("account_count", rs.getLong("account_count"))
        ));
    }

    private List<Map<String, Object>> queryGroupConcurrency(MapSqlParameterSource params) {
        return jdbcTemplate.query("""
                select g.id as group_id,
                       g.name as group_name,
                       g.platform,
                       count(a.id)::bigint as account_count,
                       coalesce(sum(a.concurrency), 0)::bigint as max_capacity
                from groups g
                left join account_groups ag on ag.group_id = g.id
                left join accounts a on a.id = ag.account_id and a.deleted_at is null
                where g.deleted_at is null
                  and (:platform is null or g.platform = :platform)
                  and (:groupId is null or g.id = :groupId)
                group by g.id, g.name, g.platform
                order by g.id asc
                """, params, (rs, rowNum) -> {
            Map<String, Object> row = concurrencyRow(
                    defaultString(rs.getString("platform")),
                    rs.getLong("max_capacity"),
                    Map.of("account_count", rs.getLong("account_count"))
            );
            row.put("group_id", rs.getLong("group_id"));
            row.put("group_name", defaultString(rs.getString("group_name")));
            return row;
        });
    }

    private List<Map<String, Object>> queryAccountConcurrency(MapSqlParameterSource params) {
        return jdbcTemplate.query("""
                select distinct a.id as account_id,
                       a.name as account_name,
                       a.platform,
                       coalesce(a.concurrency, 0) as max_capacity,
                       g.id as group_id,
                       g.name as group_name
                from accounts a
                left join account_groups ag on ag.account_id = a.id
                left join groups g on g.id = ag.group_id
                where a.deleted_at is null
                  and (:platform is null or a.platform = :platform)
                  and (:groupId is null or ag.group_id = :groupId)
                order by a.id asc
                """, params, (rs, rowNum) -> {
            Map<String, Object> row = concurrencyRow(
                    defaultString(rs.getString("platform")),
                    rs.getLong("max_capacity"),
                    Map.of()
            );
            row.put("account_id", rs.getLong("account_id"));
            row.put("account_name", defaultString(rs.getString("account_name")));
            row.put("group_id", rs.getObject("group_id", Long.class));
            row.put("group_name", defaultString(rs.getString("group_name")));
            return row;
        });
    }

    private List<Map<String, Object>> queryPlatformAvailability(MapSqlParameterSource params) {
        return jdbcTemplate.query("""
                select a.platform,
                       count(*)::bigint as total_accounts,
                       count(*) filter (where a.status = 'active')::bigint as active_accounts,
                       count(*) filter (where a.status = 'active' and coalesce(a.schedulable, true))::bigint as available_accounts,
                       count(*) filter (where a.status = 'disabled')::bigint as disabled_accounts,
                       count(*) filter (where a.status = 'error')::bigint as error_accounts,
                       count(*) filter (where a.rate_limit_reset_at is not null and a.rate_limit_reset_at > now())::bigint as rate_limited_accounts,
                       count(*) filter (where a.overload_until is not null and a.overload_until > now())::bigint as overloaded_accounts
                from accounts a
                left join account_groups ag on ag.account_id = a.id
                where a.deleted_at is null
                  and (:platform is null or a.platform = :platform)
                  and (:groupId is null or ag.group_id = :groupId)
                group by a.platform
                order by a.platform asc
                """, params, (rs, rowNum) -> availabilityRow(rs));
    }

    private List<Map<String, Object>> queryGroupAvailability(MapSqlParameterSource params) {
        return jdbcTemplate.query("""
                select g.id as group_id,
                       g.name as group_name,
                       g.platform,
                       count(a.id)::bigint as total_accounts,
                       count(a.id) filter (where a.status = 'active')::bigint as active_accounts,
                       count(a.id) filter (where a.status = 'active' and coalesce(a.schedulable, true))::bigint as available_accounts,
                       count(a.id) filter (where a.status = 'disabled')::bigint as disabled_accounts,
                       count(a.id) filter (where a.status = 'error')::bigint as error_accounts,
                       count(a.id) filter (where a.rate_limit_reset_at is not null and a.rate_limit_reset_at > now())::bigint as rate_limited_accounts,
                       count(a.id) filter (where a.overload_until is not null and a.overload_until > now())::bigint as overloaded_accounts
                from groups g
                left join account_groups ag on ag.group_id = g.id
                left join accounts a on a.id = ag.account_id and a.deleted_at is null
                where g.deleted_at is null
                  and (:platform is null or g.platform = :platform)
                  and (:groupId is null or g.id = :groupId)
                group by g.id, g.name, g.platform
                order by g.id asc
                """, params, (rs, rowNum) -> {
            Map<String, Object> row = availabilityRow(rs);
            row.put("group_id", rs.getLong("group_id"));
            row.put("group_name", defaultString(rs.getString("group_name")));
            return row;
        });
    }

    private List<Map<String, Object>> queryAccountAvailability(MapSqlParameterSource params) {
        return jdbcTemplate.query("""
                select distinct a.id as account_id,
                       a.name as account_name,
                       a.platform,
                       a.status,
                       coalesce(a.schedulable, true) as schedulable,
                       a.rate_limit_reset_at,
                       a.overload_until,
                       a.error_message,
                       g.id as group_id,
                       g.name as group_name
                from accounts a
                left join account_groups ag on ag.account_id = a.id
                left join groups g on g.id = ag.group_id
                where a.deleted_at is null
                  and (:platform is null or a.platform = :platform)
                  and (:groupId is null or ag.group_id = :groupId)
                order by a.id asc
                """, params, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            String status = defaultString(rs.getString("status"));
            boolean schedulable = rs.getBoolean("schedulable");
            Timestamp rateLimitResetAt = rs.getTimestamp("rate_limit_reset_at");
            Timestamp overloadUntil = rs.getTimestamp("overload_until");
            Instant now = Instant.now();
            boolean rateLimited = rateLimitResetAt != null && rateLimitResetAt.toInstant().isAfter(now);
            boolean overloaded = overloadUntil != null && overloadUntil.toInstant().isAfter(now);
            row.put("account_id", rs.getLong("account_id"));
            row.put("account_name", defaultString(rs.getString("account_name")));
            row.put("platform", defaultString(rs.getString("platform")));
            row.put("group_id", rs.getObject("group_id", Long.class));
            row.put("group_name", defaultString(rs.getString("group_name")));
            row.put("status", status);
            row.put("schedulable", schedulable);
            row.put("is_available", "active".equals(status) && schedulable && !rateLimited && !overloaded);
            row.put("is_rate_limited", rateLimited);
            row.put("rate_limit_reset_at", toIsoString(rateLimitResetAt));
            row.put("rate_limit_remaining_sec", remainingSeconds(rateLimitResetAt, now));
            row.put("is_overloaded", overloaded);
            row.put("overload_until", toIsoString(overloadUntil));
            row.put("overload_remaining_sec", remainingSeconds(overloadUntil, now));
            row.put("has_error", "error".equals(status) || !blankToEmpty(rs.getString("error_message")).isEmpty());
            row.put("error_message", defaultString(rs.getString("error_message")));
            return row;
        });
    }

    private Map<String, Object> keyedMap(List<Map<String, Object>> rows, String keyField) {
        Map<String, Object> keyed = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get(keyField);
            if (key != null && !String.valueOf(key).isBlank()) {
                keyed.put(String.valueOf(key), row);
            }
        }
        return keyed;
    }

    private Map<String, Object> concurrencyRow(String platform, long maxCapacity, Map<String, Object> extra) {
        Map<String, Object> row = new LinkedHashMap<>(extra);
        row.put("platform", platform);
        row.put("current_in_use", 0L);
        row.put("max_capacity", maxCapacity);
        row.put("waiting_in_queue", 0L);
        row.put("load_percentage", 0.0d);
        return row;
    }

    private Map<String, Object> availabilityRow(ResultSet rs) throws SQLException {
        long total = rs.getLong("total_accounts");
        long available = rs.getLong("available_accounts");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("platform", defaultString(rs.getString("platform")));
        row.put("total_accounts", total);
        row.put("active_accounts", rs.getLong("active_accounts"));
        row.put("available_accounts", available);
        row.put("available_count", available);
        row.put("disabled_accounts", rs.getLong("disabled_accounts"));
        row.put("error_accounts", rs.getLong("error_accounts"));
        row.put("error_count", rs.getLong("error_accounts"));
        row.put("rate_limited_accounts", rs.getLong("rate_limited_accounts"));
        row.put("rate_limit_count", rs.getLong("rate_limited_accounts"));
        row.put("overloaded_accounts", rs.getLong("overloaded_accounts"));
        row.put("available_ratio", total <= 0 ? 0.0d : roundTo4DP(available / (double) total));
        return row;
    }

    private Long remainingSeconds(Timestamp timestamp, Instant now) {
        if (timestamp == null) {
            return null;
        }
        long seconds = timestamp.toInstant().getEpochSecond() - now.getEpochSecond();
        return Math.max(0L, seconds);
    }

    private Map<String, Object> getLatestSystemMetrics() {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select id, created_at, window_minutes, platform, group_id, success_count, error_count_total,
                       business_limited_count, error_count_sla, upstream_error_count_excl_429_529,
                       upstream_429_count, upstream_529_count, token_consumed, qps, tps,
                       cpu_usage_percent, memory_used_mb, memory_total_mb, memory_usage_percent,
                       db_ok, redis_ok, db_conn_active, db_conn_idle, db_conn_waiting,
                       thread_count, concurrency_queue_depth
                from ops_system_metrics
                order by created_at desc
                limit 1
                """, new MapSqlParameterSource(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("created_at", toIsoString(rs.getTimestamp("created_at")));
            row.put("window_minutes", rs.getInt("window_minutes"));
            row.put("platform", defaultString(rs.getString("platform")));
            row.put("group_id", rs.getObject("group_id", Long.class));
            row.put("success_count", rs.getLong("success_count"));
            row.put("error_count_total", rs.getLong("error_count_total"));
            row.put("business_limited_count", rs.getLong("business_limited_count"));
            row.put("error_count_sla", rs.getLong("error_count_sla"));
            row.put("upstream_error_count_excl_429_529", rs.getLong("upstream_error_count_excl_429_529"));
            row.put("upstream_429_count", rs.getLong("upstream_429_count"));
            row.put("upstream_529_count", rs.getLong("upstream_529_count"));
            row.put("token_consumed", rs.getLong("token_consumed"));
            row.put("qps", rs.getObject("qps", Double.class));
            row.put("tps", rs.getObject("tps", Double.class));
            row.put("cpu_usage_percent", rs.getObject("cpu_usage_percent", Double.class));
            row.put("memory_used_mb", rs.getObject("memory_used_mb", Long.class));
            row.put("memory_total_mb", rs.getObject("memory_total_mb", Long.class));
            row.put("memory_usage_percent", rs.getObject("memory_usage_percent", Double.class));
            row.put("db_ok", rs.getObject("db_ok", Boolean.class));
            row.put("redis_ok", rs.getObject("redis_ok", Boolean.class));
            row.put("db_conn_active", rs.getObject("db_conn_active", Integer.class));
            row.put("db_conn_idle", rs.getObject("db_conn_idle", Integer.class));
            row.put("db_conn_waiting", rs.getObject("db_conn_waiting", Integer.class));
            row.put("thread_count", rs.getObject("thread_count", Integer.class));
            row.put("concurrency_queue_depth", rs.getObject("concurrency_queue_depth", Integer.class));
            return row;
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> listJobHeartbeats() {
        return jdbcTemplate.query("""
                select job_name, last_run_at, last_success_at, last_error_at, last_error,
                       last_duration_ms, updated_at
                from ops_job_heartbeats
                order by job_name asc
                """, new MapSqlParameterSource(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("job_name", defaultString(rs.getString("job_name")));
            row.put("last_run_at", toIsoString(rs.getTimestamp("last_run_at")));
            row.put("last_success_at", toIsoString(rs.getTimestamp("last_success_at")));
            row.put("last_error_at", toIsoString(rs.getTimestamp("last_error_at")));
            row.put("last_error", defaultString(rs.getString("last_error")));
            row.put("last_duration_ms", rs.getObject("last_duration_ms", Long.class));
            row.put("updated_at", toIsoString(rs.getTimestamp("updated_at")));
            return row;
        });
    }

    private List<Map<String, Object>> queryThroughputByPlatform(Instant start, Instant end, String platform, Long groupId) {
        if (blankToNull(platform) != null || (groupId != null && groupId > 0)) {
            return List.of();
        }
        return jdbcTemplate.query("""
                with usage_totals as (
                  select coalesce(nullif(g.platform, ''), a.platform) as platform,
                         count(*) as success_count,
                         coalesce(sum(ul.input_tokens + ul.output_tokens + ul.cache_creation_tokens + ul.cache_read_tokens), 0) as token_consumed
                  from usage_logs ul
                  left join groups g on g.id = ul.group_id
                  left join accounts a on a.id = ul.account_id
                  where ul.created_at >= :startTime and ul.created_at < :endTime
                  group by 1
                ),
                error_totals as (
                  select e.platform, count(*) as error_count
                  from ops_error_logs e
                  where e.created_at >= :startTime and e.created_at < :endTime
                    and e.is_count_tokens = false
                    and coalesce(e.status_code, 0) >= 400
                  group by 1
                )
                select coalesce(u.platform, e.platform) as platform,
                       coalesce(u.success_count, 0) + coalesce(e.error_count, 0) as request_count,
                       coalesce(u.token_consumed, 0) as token_consumed
                from usage_totals u
                full outer join error_totals e on e.platform = u.platform
                where coalesce(u.platform, e.platform) is not null and coalesce(u.platform, e.platform) <> ''
                order by request_count desc
                """, opsTimeParams(start, end, null, null), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("platform", defaultString(rs.getString("platform")));
            row.put("request_count", rs.getLong("request_count"));
            row.put("token_consumed", rs.getLong("token_consumed"));
            return row;
        });
    }

    private List<Map<String, Object>> queryThroughputTopGroups(Instant start, Instant end, String platform, Long groupId) {
        String normalizedPlatform = blankToNull(platform);
        if (normalizedPlatform == null || (groupId != null && groupId > 0)) {
            return List.of();
        }
        return jdbcTemplate.query("""
                with usage_totals as (
                  select ul.group_id, g.name as group_name,
                         count(*) as success_count,
                         coalesce(sum(ul.input_tokens + ul.output_tokens + ul.cache_creation_tokens + ul.cache_read_tokens), 0) as token_consumed
                  from usage_logs ul
                  join groups g on g.id = ul.group_id
                  where ul.created_at >= :startTime and ul.created_at < :endTime
                    and g.platform = :platform
                  group by 1, 2
                ),
                error_totals as (
                  select e.group_id, count(*) as error_count
                  from ops_error_logs e
                  where e.created_at >= :startTime and e.created_at < :endTime
                    and e.platform = :platform
                    and e.group_id is not null
                    and e.is_count_tokens = false
                    and coalesce(e.status_code, 0) >= 400
                  group by 1
                )
                select coalesce(u.group_id, e.group_id) as group_id,
                       coalesce(u.group_name, g.name, '') as group_name,
                       coalesce(u.success_count, 0) + coalesce(e.error_count, 0) as request_count,
                       coalesce(u.token_consumed, 0) as token_consumed
                from usage_totals u
                full outer join error_totals e on e.group_id = u.group_id
                left join groups g on g.id = coalesce(u.group_id, e.group_id)
                where coalesce(u.group_id, e.group_id) is not null
                order by request_count desc
                limit 10
                """, opsTimeParams(start, end, normalizedPlatform, null), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group_id", rs.getLong("group_id"));
            row.put("group_name", defaultString(rs.getString("group_name")));
            row.put("request_count", rs.getLong("request_count"));
            row.put("token_consumed", rs.getLong("token_consumed"));
            return row;
        });
    }

    private String usageBucketExpression(int bucketSeconds) {
        if (bucketSeconds == 3600) {
            return "date_trunc('hour', ul.created_at)";
        }
        if (bucketSeconds == 300) {
            return "to_timestamp(floor(extract(epoch from ul.created_at) / 300) * 300)";
        }
        return "date_trunc('minute', ul.created_at)";
    }

    private String errorBucketExpression(int bucketSeconds) {
        if (bucketSeconds == 3600) {
            return "date_trunc('hour', e.created_at)";
        }
        if (bucketSeconds == 300) {
            return "to_timestamp(floor(extract(epoch from e.created_at) / 300) * 300)";
        }
        return "date_trunc('minute', e.created_at)";
    }

    private List<Map<String, Object>> fillThroughputBuckets(Instant start, Instant end, int bucketSeconds, List<Map<String, Object>> points) {
        Map<String, Map<String, Object>> byBucket = indexByBucket(points);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Instant cursor = floorToBucket(start, bucketSeconds); cursor.isBefore(end); cursor = cursor.plusSeconds(bucketSeconds)) {
            String key = toIsoString(timestamp(cursor));
            Map<String, Object> existing = byBucket.get(key);
            if (existing != null) {
                out.add(existing);
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("bucket_start", key);
            point.put("request_count", 0L);
            point.put("token_consumed", 0L);
            point.put("switch_count", 0L);
            point.put("qps", 0.0d);
            point.put("tps", 0.0d);
            out.add(point);
        }
        return out;
    }

    private List<Map<String, Object>> fillErrorTrendBuckets(Instant start, Instant end, int bucketSeconds, List<Map<String, Object>> points) {
        Map<String, Map<String, Object>> byBucket = indexByBucket(points);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Instant cursor = floorToBucket(start, bucketSeconds); cursor.isBefore(end); cursor = cursor.plusSeconds(bucketSeconds)) {
            String key = toIsoString(timestamp(cursor));
            Map<String, Object> existing = byBucket.get(key);
            if (existing != null) {
                out.add(existing);
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("bucket_start", key);
            point.put("error_count_total", 0L);
            point.put("business_limited_count", 0L);
            point.put("error_count_sla", 0L);
            point.put("upstream_error_count_excl_429_529", 0L);
            point.put("upstream_429_count", 0L);
            point.put("upstream_529_count", 0L);
            out.add(point);
        }
        return out;
    }

    private Map<String, Map<String, Object>> indexByBucket(List<Map<String, Object>> points) {
        Map<String, Map<String, Object>> byBucket = new LinkedHashMap<>();
        for (Map<String, Object> point : points) {
            Object bucket = point.get("bucket_start");
            if (bucket != null) {
                byBucket.put(String.valueOf(bucket), point);
            }
        }
        return byBucket;
    }

    private Instant floorToBucket(Instant instant, int bucketSeconds) {
        long epoch = instant.getEpochSecond();
        return Instant.ofEpochSecond(epoch - Math.floorMod(epoch, bucketSeconds));
    }

    private int safeBucketSeconds(int bucketSeconds) {
        return bucketSeconds == 300 || bucketSeconds == 3600 ? bucketSeconds : 60;
    }

    private String bucketLabel(int bucketSeconds) {
        if (bucketSeconds == 3600) {
            return "1h";
        }
        if (bucketSeconds == 300) {
            return "5m";
        }
        return "1m";
    }

    private Map<String, Object> rateSummary(double current, double peak, double avg) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("current", current);
        payload.put("peak", peak);
        payload.put("avg", avg);
        return payload;
    }

    private Map<String, Object> percentiles(
            Double p50,
            Double p90,
            Double p95,
            Double p99,
            Double avg,
            Integer max
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("p50_ms", roundedLong(p50));
        payload.put("p90_ms", roundedLong(p90));
        payload.put("p95_ms", roundedLong(p95));
        payload.put("p99_ms", roundedLong(p99));
        payload.put("avg_ms", roundedLong(avg));
        payload.put("max_ms", max);
        return payload;
    }

    private Long roundedLong(Double value) {
        return value == null ? null : Math.round(value);
    }

    private double dashboardHealthScore(double errorRate, double upstreamErrorRate) {
        double penalty = (errorRate * 100.0d * 4.0d) + (upstreamErrorRate * 100.0d * 2.0d);
        return Math.max(0.0d, roundTo1DP(100.0d - penalty));
    }

    private double safeDivide(long numerator, long denominator) {
        return denominator == 0L ? 0.0d : numerator / (double) denominator;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String raw && !raw.isBlank()) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String raw && !raw.isBlank()) {
            try {
                return Double.parseDouble(raw.trim());
            } catch (NumberFormatException ignored) {
                return 0.0d;
            }
        }
        return 0.0d;
    }

    private double roundTo1DP(double value) {
        return Math.round(value * 10.0d) / 10.0d;
    }

    private double roundTo4DP(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private MapSqlParameterSource alertRuleParams(Map<String, Object> input) {
        Map<String, Object> source = input == null ? Map.of() : input;
        return new MapSqlParameterSource()
                .addValue("name", source.get("name"))
                .addValue("description", source.get("description"))
                .addValue("enabled", source.get("enabled"))
                .addValue("severity", source.get("severity"))
                .addValue("metricType", source.get("metric_type"))
                .addValue("operator", source.get("operator"))
                .addValue("threshold", source.get("threshold"))
                .addValue("windowMinutes", source.get("window_minutes"))
                .addValue("sustainedMinutes", source.get("sustained_minutes"))
                .addValue("cooldownMinutes", source.get("cooldown_minutes"))
                .addValue("notifyEmail", source.get("notify_email"))
                .addValue("filters", jsonHelper.writeJson(source.getOrDefault("filters", Map.of())));
    }

    private String systemLogWhere() {
        return """
                where (:level is null or l.level = :level)
                  and (:component is null or l.component = :component)
                  and (:requestId is null or l.request_id = :requestId)
                  and (:clientRequestId is null or l.client_request_id = :clientRequestId)
                  and (:userId is null or l.user_id = :userId)
                  and (:accountId is null or l.account_id = :accountId)
                  and (:platform is null or l.platform = :platform)
                  and (:model is null or l.model = :model)
                  and (:startTime is null or l.created_at >= :startTime)
                  and (:endTime is null or l.created_at <= :endTime)
                  and (
                        :query is null
                        or l.message ilike :likeQuery
                        or coalesce(l.component, '') ilike :likeQuery
                        or coalesce(l.request_id, '') ilike :likeQuery
                        or coalesce(l.client_request_id, '') ilike :likeQuery
                  )
                """;
    }

    private MapSqlParameterSource systemLogParams(Map<String, Object> filter) {
        Map<String, Object> source = filter == null ? Map.of() : filter;
        String query = blankToNull((String) source.get("q"));
        return new MapSqlParameterSource()
                .addValue("level", blankToNull((String) source.get("level")))
                .addValue("component", blankToNull((String) source.get("component")))
                .addValue("requestId", blankToNull((String) source.get("request_id")))
                .addValue("clientRequestId", blankToNull((String) source.get("client_request_id")))
                .addValue("userId", source.get("user_id"))
                .addValue("accountId", source.get("account_id"))
                .addValue("platform", blankToNull((String) source.get("platform")))
                .addValue("model", blankToNull((String) source.get("model")))
                .addValue("startTime", timestamp((Instant) source.get("start_time")))
                .addValue("endTime", timestamp((Instant) source.get("end_time")))
                .addValue("query", query)
                .addValue("likeQuery", query == null ? null : "%" + query + "%");
    }

    private String errorLogWhere(boolean upstreamOnly) {
        StringBuilder builder = new StringBuilder("""
                where (:platform is null or e.platform = :platform)
                  and (:groupId is null or e.group_id = :groupId)
                  and (:accountId is null or e.account_id = :accountId)
                  and (:phase is null or e.error_phase = :phase)
                  and (:errorOwner is null or e.error_owner = :errorOwner)
                  and (:errorSource is null or e.error_source = :errorSource)
                  and (:resolved is null or e.resolved = :resolved)
                  and (:requestId is null or e.request_id = :requestId)
                  and (:clientRequestId is null or e.client_request_id = :clientRequestId)
                  and (:startTime is null or e.created_at >= :startTime)
                  and (:endTime is null or e.created_at <= :endTime)
                  and (
                        :query is null
                        or coalesce(e.error_message, '') ilike :likeQuery
                        or coalesce(e.request_id, '') ilike :likeQuery
                        or coalesce(e.client_request_id, '') ilike :likeQuery
                  )
                """);
        if (upstreamOnly) {
            builder.append(" and e.error_phase = 'upstream' and e.error_owner = 'provider' ");
        }
        return builder.toString();
    }

    private MapSqlParameterSource errorLogParams(Map<String, Object> filter, boolean upstreamOnly) {
        Map<String, Object> source = filter == null ? Map.of() : filter;
        String query = blankToNull((String) source.get("q"));
        Boolean resolved = null;
        Object resolvedValue = source.get("resolved");
        if (resolvedValue instanceof Boolean boolValue) {
            resolved = boolValue;
        } else if (resolvedValue instanceof String raw) {
            String normalized = raw.trim().toLowerCase();
            if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
                resolved = true;
            } else if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
                resolved = false;
            }
        }
        return new MapSqlParameterSource()
                .addValue("platform", blankToNull((String) source.get("platform")))
                .addValue("groupId", source.get("group_id"))
                .addValue("accountId", source.get("account_id"))
                .addValue("phase", upstreamOnly ? "upstream" : blankToNull((String) source.get("phase")))
                .addValue("errorOwner", upstreamOnly ? "provider" : blankToNull((String) source.get("error_owner")))
                .addValue("errorSource", blankToNull((String) source.get("error_source")))
                .addValue("resolved", resolved)
                .addValue("requestId", blankToNull((String) source.get("request_id")))
                .addValue("clientRequestId", blankToNull((String) source.get("client_request_id")))
                .addValue("startTime", timestamp((Instant) source.get("start_time")))
                .addValue("endTime", timestamp((Instant) source.get("end_time")))
                .addValue("query", query)
                .addValue("likeQuery", query == null ? null : "%" + query + "%");
    }

    private String requestDetailsWhere(Map<String, Object> filter) {
        return """
                where (:kind is null or kind = :kind)
                  and (:platform is null or platform = :platform)
                  and (:groupId is null or group_id = :groupId)
                  and (:userId is null or user_id = :userId)
                  and (:apiKeyId is null or api_key_id = :apiKeyId)
                  and (:accountId is null or account_id = :accountId)
                  and (:model is null or model = :model)
                  and (:requestId is null or request_id = :requestId)
                  and (:minDurationMs is null or duration_ms >= :minDurationMs)
                  and (:maxDurationMs is null or duration_ms <= :maxDurationMs)
                  and (
                        :query is null
                        or lower(coalesce(request_id, '')) like :likeQuery
                        or lower(coalesce(model, '')) like :likeQuery
                        or lower(coalesce(message, '')) like :likeQuery
                  )
                """;
    }

    private String requestDetailsSort(Map<String, Object> filter) {
        String sort = blankToEmpty((String) (filter == null ? null : filter.get("sort"))).toLowerCase(Locale.ROOT);
        if ("duration_desc".equals(sort)) {
            return "order by duration_ms desc nulls last, created_at desc";
        }
        return "order by created_at desc";
    }

    private MapSqlParameterSource requestDetailsParams(Map<String, Object> filter) {
        Map<String, Object> source = filter == null ? Map.of() : filter;
        String kind = blankToNull((String) source.get("kind"));
        if ("all".equalsIgnoreCase(kind)) {
            kind = null;
        }
        String query = blankToNull((String) source.get("q"));
        return new MapSqlParameterSource()
                .addValue("kind", kind)
                .addValue("platform", blankToNull((String) source.get("platform")))
                .addValue("groupId", source.get("group_id"))
                .addValue("userId", source.get("user_id"))
                .addValue("apiKeyId", source.get("api_key_id"))
                .addValue("accountId", source.get("account_id"))
                .addValue("model", blankToNull((String) source.get("model")))
                .addValue("requestId", blankToNull((String) source.get("request_id")))
                .addValue("minDurationMs", source.get("min_duration_ms"))
                .addValue("maxDurationMs", source.get("max_duration_ms"))
                .addValue("startTime", timestamp((Instant) source.get("start_time")))
                .addValue("endTime", timestamp((Instant) source.get("end_time")))
                .addValue("query", query)
                .addValue("likeQuery", query == null ? null : "%" + query.toLowerCase(Locale.ROOT) + "%");
    }

    private Map<String, Object> mapAlertRule(ResultSet rs) throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", rs.getLong("id"));
        payload.put("name", defaultString(rs.getString("name")));
        payload.put("description", rs.getString("description"));
        payload.put("enabled", rs.getBoolean("enabled"));
        payload.put("metric_type", defaultString(rs.getString("metric_type")));
        payload.put("operator", defaultString(rs.getString("operator")));
        payload.put("threshold", rs.getDouble("threshold"));
        payload.put("window_minutes", rs.getInt("window_minutes"));
        payload.put("sustained_minutes", rs.getInt("sustained_minutes"));
        payload.put("severity", defaultString(rs.getString("severity")));
        payload.put("cooldown_minutes", rs.getInt("cooldown_minutes"));
        payload.put("notify_email", rs.getBoolean("notify_email"));
        payload.put("filters", new LinkedHashMap<>(jsonHelper.readObjectMap(rs.getString("filters_json"))));
        payload.put("created_at", toIsoString(rs.getTimestamp("created_at")));
        payload.put("updated_at", toIsoString(rs.getTimestamp("updated_at")));
        payload.put("last_triggered_at", toIsoString(rs.getTimestamp("last_triggered_at")));
        return payload;
    }

    private Map<String, Object> mapAlertEvent(ResultSet rs) throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", rs.getLong("id"));
        payload.put("rule_id", rs.getLong("rule_id"));
        payload.put("severity", defaultString(rs.getString("severity")));
        payload.put("status", defaultString(rs.getString("status")));
        payload.put("title", rs.getString("title"));
        payload.put("description", rs.getString("description"));
        payload.put("metric_value", rs.getObject("metric_value", Double.class));
        payload.put("threshold_value", rs.getObject("threshold_value", Double.class));
        payload.put("dimensions", new LinkedHashMap<>(jsonHelper.readObjectMap(rs.getString("dimensions_json"))));
        payload.put("fired_at", toIsoString(rs.getTimestamp("fired_at")));
        payload.put("resolved_at", toIsoString(rs.getTimestamp("resolved_at")));
        payload.put("email_sent", rs.getBoolean("email_sent"));
        payload.put("created_at", toIsoString(rs.getTimestamp("created_at")));
        return payload;
    }

    private Map<String, Object> mapSystemLog(ResultSet rs) throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", rs.getLong("id"));
        payload.put("created_at", toIsoString(rs.getTimestamp("created_at")));
        payload.put("level", defaultString(rs.getString("level")));
        payload.put("component", defaultString(rs.getString("component")));
        payload.put("message", defaultString(rs.getString("message")));
        payload.put("request_id", rs.getString("request_id"));
        payload.put("client_request_id", rs.getString("client_request_id"));
        payload.put("user_id", rs.getObject("user_id", Long.class));
        payload.put("account_id", rs.getObject("account_id", Long.class));
        payload.put("platform", defaultString(rs.getString("platform")));
        payload.put("model", defaultString(rs.getString("model")));
        payload.put("extra", new LinkedHashMap<>(jsonHelper.readObjectMap(rs.getString("extra_json"))));
        return payload;
    }

    private Map<String, Object> mapErrorLog(ResultSet rs) throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", rs.getLong("id"));
        payload.put("created_at", toIsoString(rs.getTimestamp("created_at")));
        payload.put("phase", defaultString(rs.getString("error_phase")));
        payload.put("type", defaultString(rs.getString("error_type")));
        payload.put("error_owner", defaultString(rs.getString("error_owner")));
        payload.put("error_source", defaultString(rs.getString("error_source")));
        payload.put("severity", defaultString(rs.getString("severity")));
        payload.put("status_code", rs.getObject("status_code", Integer.class));
        payload.put("platform", defaultString(rs.getString("platform")));
        payload.put("model", defaultString(rs.getString("model")));
        payload.put("is_retryable", rs.getBoolean("is_retryable"));
        payload.put("retry_count", rs.getInt("retry_count"));
        payload.put("resolved", rs.getBoolean("resolved"));
        payload.put("resolved_at", toIsoString(rs.getTimestamp("resolved_at")));
        payload.put("resolved_by_user_id", rs.getObject("resolved_by_user_id", Long.class));
        payload.put("resolved_retry_id", rs.getObject("resolved_retry_id", Long.class));
        payload.put("client_request_id", defaultString(rs.getString("client_request_id")));
        payload.put("request_id", defaultString(rs.getString("request_id")));
        payload.put("message", defaultString(rs.getString("error_message")));
        payload.put("user_id", rs.getObject("user_id", Long.class));
        payload.put("user_email", defaultString(rs.getString("user_email")));
        payload.put("api_key_id", rs.getObject("api_key_id", Long.class));
        payload.put("account_id", rs.getObject("account_id", Long.class));
        payload.put("account_name", defaultString(rs.getString("account_name")));
        payload.put("group_id", rs.getObject("group_id", Long.class));
        payload.put("group_name", defaultString(rs.getString("group_name")));
        payload.put("client_ip", rs.getString("client_ip"));
        payload.put("request_path", defaultString(rs.getString("request_path")));
        payload.put("stream", rs.getBoolean("stream"));
        payload.put("inbound_endpoint", rs.getString("inbound_endpoint"));
        payload.put("upstream_endpoint", rs.getString("upstream_endpoint"));
        payload.put("requested_model", rs.getString("requested_model"));
        payload.put("upstream_model", rs.getString("upstream_model"));
        payload.put("request_type", rs.getObject("request_type", Integer.class));
        return payload;
    }

    private Map<String, Object> mapErrorDetail(ResultSet rs) throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>(mapErrorLog(rs));
        payload.put("error_body", defaultString(rs.getString("error_body")));
        payload.put("user_agent", defaultString(rs.getString("user_agent")));
        payload.put("upstream_status_code", rs.getObject("upstream_status_code", Integer.class));
        payload.put("upstream_error_message", defaultString(rs.getString("upstream_error_message")));
        payload.put("upstream_error_detail", defaultString(rs.getString("upstream_error_detail")));
        payload.put("upstream_errors", defaultString(rs.getString("upstream_errors_json")));
        payload.put("auth_latency_ms", rs.getObject("auth_latency_ms", Long.class));
        payload.put("routing_latency_ms", rs.getObject("routing_latency_ms", Long.class));
        payload.put("upstream_latency_ms", rs.getObject("upstream_latency_ms", Long.class));
        payload.put("response_latency_ms", rs.getObject("response_latency_ms", Long.class));
        payload.put("time_to_first_token_ms", rs.getObject("time_to_first_token_ms", Long.class));
        payload.put("request_body", defaultString(rs.getString("request_body_json")));
        payload.put("request_headers", defaultString(rs.getString("request_headers_json")));
        payload.put("request_body_truncated", rs.getBoolean("request_body_truncated"));
        payload.put("request_body_bytes", rs.getObject("request_body_bytes", Integer.class));
        payload.put("is_business_limited", rs.getBoolean("is_business_limited"));
        return payload;
    }

    private Map<String, Object> mapRequestDetail(ResultSet rs) throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", defaultString(rs.getString("kind")));
        payload.put("created_at", toIsoString(rs.getTimestamp("created_at")));
        payload.put("request_id", defaultString(rs.getString("request_id")));
        String platform = blankToEmpty(rs.getString("platform"));
        payload.put("platform", platform.isEmpty() ? "unknown" : platform);
        payload.put("model", defaultString(rs.getString("model")));
        payload.put("duration_ms", rs.getObject("duration_ms", Integer.class));
        payload.put("status_code", rs.getObject("status_code", Integer.class));
        payload.put("error_id", rs.getObject("error_id", Long.class));
        payload.put("phase", defaultString(rs.getString("phase")));
        payload.put("severity", defaultString(rs.getString("severity")));
        payload.put("message", defaultString(rs.getString("message")));
        payload.put("user_id", rs.getObject("user_id", Long.class));
        payload.put("api_key_id", rs.getObject("api_key_id", Long.class));
        payload.put("account_id", rs.getObject("account_id", Long.class));
        payload.put("group_id", rs.getObject("group_id", Long.class));
        payload.put("stream", rs.getBoolean("stream"));
        return payload;
    }

    private Map<String, Object> mapRetryAttempt(ResultSet rs) throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", rs.getLong("id"));
        payload.put("created_at", toIsoString(rs.getTimestamp("created_at")));
        payload.put("requested_by_user_id", rs.getObject("requested_by_user_id", Long.class));
        payload.put("source_error_id", rs.getObject("source_error_id", Long.class));
        payload.put("mode", defaultString(rs.getString("mode")));
        payload.put("pinned_account_id", rs.getObject("pinned_account_id", Long.class));
        payload.put("status", defaultString(rs.getString("status")));
        payload.put("started_at", toIsoString(rs.getTimestamp("started_at")));
        payload.put("finished_at", toIsoString(rs.getTimestamp("finished_at")));
        payload.put("duration_ms", rs.getObject("duration_ms", Long.class));
        payload.put("success", rs.getObject("success", Boolean.class));
        payload.put("http_status_code", rs.getObject("http_status_code", Integer.class));
        payload.put("upstream_request_id", defaultString(rs.getString("upstream_request_id")));
        payload.put("used_account_id", rs.getObject("used_account_id", Long.class));
        payload.put("response_preview", defaultString(rs.getString("response_preview")));
        payload.put("response_truncated", rs.getBoolean("response_truncated"));
        payload.put("result_request_id", rs.getString("result_request_id"));
        payload.put("result_error_id", rs.getObject("result_error_id", Long.class));
        payload.put("error_message", defaultString(rs.getString("error_message")));
        return payload;
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC).toString();
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
