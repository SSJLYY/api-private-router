package org.apiprivaterouter.javabackend.gateway.runtime.repository;

import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayAccountSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayApiKeyRuntimeView;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayGroupSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewaySubscriptionSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayUserSummary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class GatewayRuntimeRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public GatewayRuntimeRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public Optional<GatewayApiKeyRuntimeView> findApiKey(long apiKeyId) {
        List<GatewayApiKeyRuntimeView> rows = jdbcTemplate.query("""
                select k.id, k.user_id, k.key, k.name, k.status, k.group_id,
                       k.quota, k.quota_used, k.expires_at,
                       k.rate_limit_5h, k.rate_limit_1d, k.rate_limit_7d,
                       k.usage_5h, k.usage_1d, k.usage_7d,
                       k.window_5h_start, k.window_1d_start, k.window_7d_start,
                       g.id as group_ref_id, g.name as group_name, g.platform as group_platform,
                       g.subscription_type as group_subscription_type,
                       g.daily_limit_usd as group_daily_limit_usd,
                       g.weekly_limit_usd as group_weekly_limit_usd,
                       g.monthly_limit_usd as group_monthly_limit_usd,
                       g.allow_image_generation as group_allow_image_generation,
                       g.claude_code_only as group_claude_code_only,
                       g.default_mapped_model as group_default_mapped_model,
                       g.messages_dispatch_model_config::text as group_messages_dispatch_model_config
                from api_keys k
                left join groups g on g.id = k.group_id and g.deleted_at is null
                where k.id = :id
                  and k.deleted_at is null
                limit 1
                """, new MapSqlParameterSource("id", apiKeyId), (rs, rowNum) -> mapApiKey(rs));
        return rows.stream().findFirst();
    }

    public Optional<GatewayUserSummary> findUser(long userId) {
        List<GatewayUserSummary> rows = jdbcTemplate.query("""
                select id, email, role, status, balance
                from users
                where id = :id
                  and deleted_at is null
                limit 1
                """, new MapSqlParameterSource("id", userId), (rs, rowNum) -> new GatewayUserSummary(
                rs.getLong("id"),
                defaultString(rs.getString("email")),
                defaultString(rs.getString("role")),
                defaultString(rs.getString("status")),
                toDouble(rs.getBigDecimal("balance"), 0.0d)
        ));
        return rows.stream().findFirst();
    }

    public Optional<GatewaySubscriptionSummary> findActiveSubscription(long userId, long groupId) {
        List<GatewaySubscriptionSummary> rows = jdbcTemplate.query("""
                select id, user_id, group_id, status, expires_at,
                       daily_usage_usd, weekly_usage_usd, monthly_usage_usd,
                       daily_window_start, weekly_window_start, monthly_window_start
                from user_subscriptions
                where user_id = :userId
                  and group_id = :groupId
                  and deleted_at is null
                  and status = 'active'
                order by expires_at desc nulls last, id desc
                limit 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId), (rs, rowNum) -> new GatewaySubscriptionSummary(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("group_id"),
                defaultString(rs.getString("status")),
                toIsoString(rs.getTimestamp("expires_at")),
                toDouble(rs.getBigDecimal("daily_usage_usd"), 0.0d),
                toDouble(rs.getBigDecimal("weekly_usage_usd"), 0.0d),
                toDouble(rs.getBigDecimal("monthly_usage_usd"), 0.0d),
                toIsoString(rs.getTimestamp("daily_window_start")),
                toIsoString(rs.getTimestamp("weekly_window_start")),
                toIsoString(rs.getTimestamp("monthly_window_start"))
        ));
        return rows.stream().findFirst();
    }

    public Optional<GatewayGroupSummary> findGroup(long groupId) {
        if (groupId <= 0) {
            return Optional.empty();
        }
        List<GatewayGroupSummary> rows = jdbcTemplate.query("""
                select g.id as group_ref_id, g.name as group_name, g.platform as group_platform,
                       g.subscription_type as group_subscription_type,
                       g.daily_limit_usd as group_daily_limit_usd,
                       g.weekly_limit_usd as group_weekly_limit_usd,
                       g.monthly_limit_usd as group_monthly_limit_usd,
                       g.allow_image_generation as group_allow_image_generation,
                       g.claude_code_only as group_claude_code_only,
                       g.default_mapped_model as group_default_mapped_model,
                       g.messages_dispatch_model_config::text as group_messages_dispatch_model_config
                from groups g
                where g.id = :id
                  and g.deleted_at is null
                limit 1
                """, new MapSqlParameterSource("id", groupId), (rs, rowNum) -> mapGroup(rs));
        return rows.stream().findFirst();
    }

    public Optional<GatewayAccountSummary> findPreferredAccount(Long groupId, String platform) {
        return findPreferredAccount(groupId, platform, false);
    }

    public Optional<GatewayAccountSummary> findPreferredAccount(Long groupId, String platform, boolean requireCompact) {
        return findPreferredAccount(groupId, platform, requireCompact, List.of());
    }

    public Optional<GatewayAccountSummary> findPreferredAccount(Long groupId, String platform, boolean requireCompact, Long excludedAccountId) {
        return findPreferredAccount(groupId, platform, requireCompact, excludedAccountId == null ? List.of() : List.of(excludedAccountId));
    }

    public Optional<GatewayAccountSummary> findPreferredAccount(
            Long groupId,
            String platform,
            boolean requireCompact,
            Collection<Long> excludedAccountIds
    ) {
        if (groupId == null || groupId <= 0) {
            return Optional.empty();
        }
        long nowEpoch = Instant.now().getEpochSecond();
        List<Long> excludedIds = excludedAccountIds == null ? List.of() : excludedAccountIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        String normalizedPlatform = normalizePlatform(platform);
        String whereClause = buildPreferredAccountWhereClause(normalizedPlatform, requireCompact, !excludedIds.isEmpty());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("groupId", groupId)
                .addValue("nowEpoch", nowEpoch)
                .addValue("requireCompact", requireCompact);
        if (normalizedPlatform != null) {
            params.addValue("platform", normalizedPlatform);
        }
        if (!excludedIds.isEmpty()) {
            params.addValue("excludedAccountIds", excludedIds);
        }
        String orderBy = requireCompact
                ? "order by compact_support_tier desc, ag.priority asc, a.priority asc, a.id asc\n"
                : "order by ag.priority asc, a.priority asc, a.id asc\n";
        List<GatewayAccountSummary> rows = jdbcTemplate.query("""
                select a.id, a.name, a.platform, a.type, a.status, a.priority, a.proxy_id,
                       a.credentials::text as credentials_json,
                       a.extra::text as extra_json,
                       case
                           when a.platform = 'openai' and lower(coalesce(a.extra->>'openai_compact_mode', '')) = 'force_on' then 2
                           when a.platform = 'openai' and lower(coalesce(a.extra->>'openai_compact_mode', '')) = 'force_off' then 0
                           when a.platform = 'openai' and coalesce(a.extra->>'openai_compact_supported', '') = 'true' then 2
                           when a.platform = 'openai' and coalesce(a.extra->>'openai_compact_supported', '') = 'false' then 0
                           else 1
                       end as compact_support_tier
                from account_groups ag
                join accounts a on a.id = ag.account_id and a.deleted_at is null
                """ + whereClause + """
                """ + orderBy + """
                limit 1
                """, params, (rs, rowNum) -> new GatewayAccountSummary(
                rs.getLong("id"),
                defaultString(rs.getString("name")),
                defaultString(rs.getString("platform")),
                defaultString(rs.getString("type")),
                defaultString(rs.getString("status")),
                rs.getObject("priority", Integer.class),
                rs.getObject("proxy_id", Long.class),
                jsonHelper.readObjectMap(rs.getString("credentials_json")),
                jsonHelper.readObjectMap(rs.getString("extra_json"))
        ));
        return rows.stream().findFirst();
    }

    public Optional<GatewayAccountSummary> findAccountForGroup(
            Long groupId,
            long accountId,
            String platform,
            boolean requireCompact
    ) {
        if (groupId == null || groupId <= 0 || accountId <= 0) {
            return Optional.empty();
        }
        long nowEpoch = Instant.now().getEpochSecond();
        String normalizedPlatform = normalizePlatform(platform);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("groupId", groupId)
                .addValue("accountId", accountId)
                .addValue("nowEpoch", nowEpoch)
                .addValue("requireCompact", requireCompact);
        if (normalizedPlatform != null) {
            params.addValue("platform", normalizedPlatform);
        }
        String whereClause = buildBoundAccountWhereClause(normalizedPlatform, requireCompact);
        List<GatewayAccountSummary> rows = jdbcTemplate.query("""
                select a.id, a.name, a.platform, a.type, a.status, a.priority, a.proxy_id,
                       a.credentials::text as credentials_json,
                       a.extra::text as extra_json
                from account_groups ag
                join accounts a on a.id = ag.account_id and a.deleted_at is null
                """ + whereClause + """
                limit 1
                """, params, (rs, rowNum) -> new GatewayAccountSummary(
                rs.getLong("id"),
                defaultString(rs.getString("name")),
                defaultString(rs.getString("platform")),
                defaultString(rs.getString("type")),
                defaultString(rs.getString("status")),
                rs.getObject("priority", Integer.class),
                rs.getObject("proxy_id", Long.class),
                jsonHelper.readObjectMap(rs.getString("credentials_json")),
                jsonHelper.readObjectMap(rs.getString("extra_json"))
        ));
        return rows.stream().findFirst();
    }

    private String buildPreferredAccountWhereClause(String normalizedPlatform, boolean requireCompact, boolean hasExcludedIds) {
        StringBuilder sql = new StringBuilder("""
                where ag.group_id = :groupId
                  and a.schedulable = true
                  and a.status = 'active'
                  and (a.rate_limit_reset_at is null or extract(epoch from a.rate_limit_reset_at) <= :nowEpoch)
                  and (a.overload_until is null or extract(epoch from a.overload_until) <= :nowEpoch)
                  and (a.temp_unschedulable_until is null or extract(epoch from a.temp_unschedulable_until) <= :nowEpoch)
                """);
        if (normalizedPlatform != null) {
            sql.append("\n  and a.platform = :platform");
        }
        if (hasExcludedIds) {
            sql.append("\n  and a.id not in (:excludedAccountIds)");
        }
        if (requireCompact) {
            sql.append("""
                    
                      and a.platform = 'openai'
                      and (
                          lower(coalesce(a.extra->>'openai_compact_mode', '')) = 'force_on'
                          or lower(coalesce(a.extra->>'openai_compact_mode', '')) not in ('force_off')
                             and coalesce(a.extra->>'openai_compact_supported', '') <> 'false'
                      )
                    """);
        }
        sql.append('\n');
        return sql.toString();
    }

    private String buildBoundAccountWhereClause(String normalizedPlatform, boolean requireCompact) {
        StringBuilder sql = new StringBuilder("""
                where ag.group_id = :groupId
                  and a.id = :accountId
                  and a.schedulable = true
                  and a.status = 'active'
                  and (a.rate_limit_reset_at is null or extract(epoch from a.rate_limit_reset_at) <= :nowEpoch)
                  and (a.overload_until is null or extract(epoch from a.overload_until) <= :nowEpoch)
                  and (a.temp_unschedulable_until is null or extract(epoch from a.temp_unschedulable_until) <= :nowEpoch)
                """);
        if (normalizedPlatform != null) {
            sql.append("\n  and a.platform = :platform");
        }
        if (requireCompact) {
            sql.append("""
                    
                      and a.platform = 'openai'
                      and (
                          lower(coalesce(a.extra->>'openai_compact_mode', '')) = 'force_on'
                          or lower(coalesce(a.extra->>'openai_compact_mode', '')) not in ('force_off')
                             and coalesce(a.extra->>'openai_compact_supported', '') <> 'false'
                      )
                    """);
        }
        sql.append('\n');
        return sql.toString();
    }

    private GatewayApiKeyRuntimeView mapApiKey(ResultSet rs) throws SQLException {
        Timestamp window5h = rs.getTimestamp("window_5h_start");
        Timestamp window1d = rs.getTimestamp("window_1d_start");
        Timestamp window7d = rs.getTimestamp("window_7d_start");
        GatewayGroupSummary group = mapGroup(rs);
        return new GatewayApiKeyRuntimeView(
                rs.getLong("id"),
                rs.getLong("user_id"),
                defaultString(rs.getString("key")),
                defaultString(rs.getString("name")),
                normalizeStatus(rs.getString("status")),
                rs.getObject("group_id", Long.class),
                toDouble(rs.getBigDecimal("quota"), 0.0d),
                toDouble(rs.getBigDecimal("quota_used"), 0.0d),
                toIsoString(rs.getTimestamp("expires_at")),
                toDouble(rs.getBigDecimal("rate_limit_5h"), 0.0d),
                toDouble(rs.getBigDecimal("rate_limit_1d"), 0.0d),
                toDouble(rs.getBigDecimal("rate_limit_7d"), 0.0d),
                effectiveUsage(window5h, 5, toDouble(rs.getBigDecimal("usage_5h"), 0.0d)),
                effectiveUsage(window1d, 24, toDouble(rs.getBigDecimal("usage_1d"), 0.0d)),
                effectiveUsage(window7d, 24 * 7, toDouble(rs.getBigDecimal("usage_7d"), 0.0d)),
                toIsoString(window5h),
                toIsoString(window1d),
                toIsoString(window7d),
                computeResetAt(window5h, 5),
                computeResetAt(window1d, 24),
                computeResetAt(window7d, 24 * 7),
                group
        );
    }

    private GatewayGroupSummary mapGroup(ResultSet rs) throws SQLException {
        Long groupId = rs.getObject("group_ref_id", Long.class);
        if (groupId == null) {
            return null;
        }
        return new GatewayGroupSummary(
                groupId,
                defaultString(rs.getString("group_name")),
                defaultString(rs.getString("group_platform")),
                defaultString(rs.getString("group_subscription_type")),
                toNullableDouble(rs.getBigDecimal("group_daily_limit_usd")),
                toNullableDouble(rs.getBigDecimal("group_weekly_limit_usd")),
                toNullableDouble(rs.getBigDecimal("group_monthly_limit_usd")),
                rs.getBoolean("group_allow_image_generation"),
                rs.getBoolean("group_claude_code_only"),
                defaultString(rs.getString("group_default_mapped_model")),
                jsonHelper.readObject(
                        rs.getString("group_messages_dispatch_model_config"),
                        GatewayGroupSummary.MessagesDispatchModelConfig.class
                )
        );
    }

    private String normalizeStatus(String status) {
        return status == null || status.isBlank() ? "active" : status.trim();
    }

    private String normalizePlatform(String platform) {
        return platform == null ? null : platform.trim().toLowerCase();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private String computeResetAt(Timestamp start, int hours) {
        if (start == null) {
            return null;
        }
        return start.toInstant().plus(hours, ChronoUnit.HOURS).toString();
    }

    private double effectiveUsage(Timestamp windowStart, int hours, double storedUsage) {
        if (windowStart == null) {
            return 0;
        }
        Instant resetAt = windowStart.toInstant().plus(hours, ChronoUnit.HOURS);
        return resetAt.isBefore(Instant.now()) ? 0 : storedUsage;
    }

    private Double toNullableDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private double toDouble(BigDecimal value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }
}
