package org.apiprivaterouter.javabackend.userkeys.repository;

import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.usergroups.model.UserAvailableGroupResponse;
import org.apiprivaterouter.javabackend.userkeys.model.UserApiKeyResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Repository
public class UserApiKeyRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public UserApiKeyRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public PageResponse<UserApiKeyResponse> listByUser(
            long userId,
            int page,
            int pageSize,
            String search,
            String status,
            Long groupId,
            String sortBy,
            String sortOrder
    ) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = pageSize <= 0 ? 10 : Math.min(pageSize, 100);
        int offset = (normalizedPage - 1) * normalizedPageSize;
        String normalizedSearch = normalizeText(search);
        String where = buildWhereClause(normalizedSearch, status, groupId);
        MapSqlParameterSource params = baseParams(userId, normalizedSearch, status, groupId)
                .addValue("pageSize", normalizedPageSize)
                .addValue("offset", offset);
        String countSql = """
                select count(*)
                from api_keys k
                """ + where;
        String listSql = """
                select k.id, k.user_id, k.key, k.name, k.group_id, k.status, k.last_used_at,
                       k.ip_whitelist::text as ip_whitelist,
                       k.ip_blacklist::text as ip_blacklist,
                       k.quota, k.quota_used, k.expires_at,
                       k.rate_limit_5h, k.rate_limit_1d, k.rate_limit_7d,
                       k.usage_5h, k.usage_1d, k.usage_7d,
                       k.window_5h_start, k.window_1d_start, k.window_7d_start,
                       k.created_at, k.updated_at,
                       g.id as group_ref_id, g.name as group_name, g.description as group_description,
                       g.platform as group_platform, g.rate_multiplier as group_rate_multiplier,
                       g.rpm_limit as group_rpm_limit, g.is_exclusive as group_is_exclusive, g.status as group_status,
                       g.subscription_type as group_subscription_type,
                       g.daily_limit_usd as group_daily_limit_usd,
                       g.weekly_limit_usd as group_weekly_limit_usd,
                       g.monthly_limit_usd as group_monthly_limit_usd,
                       g.allow_image_generation as group_allow_image_generation,
                       g.image_rate_independent as group_image_rate_independent,
                       g.image_rate_multiplier as group_image_rate_multiplier,
                       g.image_price_1k as group_image_price_1k,
                       g.image_price_2k as group_image_price_2k,
                       g.image_price_4k as group_image_price_4k,
                       g.claude_code_only as group_claude_code_only,
                       g.fallback_group_id as group_fallback_group_id,
                       g.fallback_group_id_on_invalid_request as group_fallback_group_id_on_invalid_request,
                       g.allow_messages_dispatch as group_allow_messages_dispatch,
                       g.default_mapped_model as group_default_mapped_model,
                       g.messages_dispatch_model_config::text as group_messages_dispatch_model_config,
                       g.require_oauth_only as group_require_oauth_only,
                       g.require_privacy_set as group_require_privacy_set,
                       g.supported_model_scopes::text as group_supported_model_scopes,
                       g.created_at as group_created_at,
                       g.updated_at as group_updated_at
                from api_keys k
                left join groups g on g.id = k.group_id and g.deleted_at is null
                """ + where + " order by " + resolveSortBy(sortBy) + " " + resolveSortOrder(sortOrder)
                + ", k.id desc limit :pageSize offset :offset";
        Long total = jdbcTemplate.queryForObject(countSql, params, Long.class);
        List<UserApiKeyResponse> items = jdbcTemplate.query(listSql, params, (rs, rowNum) -> mapApiKey(rs));
        return new PageResponse<>(items, total == null ? 0 : total, normalizedPage, normalizedPageSize);
    }

    public Optional<UserApiKeyResponse> findByIdForUser(long id, long userId) {
        List<UserApiKeyResponse> rows = jdbcTemplate.query("""
                select k.id, k.user_id, k.key, k.name, k.group_id, k.status, k.last_used_at,
                       k.ip_whitelist::text as ip_whitelist,
                       k.ip_blacklist::text as ip_blacklist,
                       k.quota, k.quota_used, k.expires_at,
                       k.rate_limit_5h, k.rate_limit_1d, k.rate_limit_7d,
                       k.usage_5h, k.usage_1d, k.usage_7d,
                       k.window_5h_start, k.window_1d_start, k.window_7d_start,
                       k.created_at, k.updated_at,
                       g.id as group_ref_id, g.name as group_name, g.description as group_description,
                       g.platform as group_platform, g.rate_multiplier as group_rate_multiplier,
                       g.rpm_limit as group_rpm_limit, g.is_exclusive as group_is_exclusive, g.status as group_status,
                       g.subscription_type as group_subscription_type,
                       g.daily_limit_usd as group_daily_limit_usd,
                       g.weekly_limit_usd as group_weekly_limit_usd,
                       g.monthly_limit_usd as group_monthly_limit_usd,
                       g.allow_image_generation as group_allow_image_generation,
                       g.image_rate_independent as group_image_rate_independent,
                       g.image_rate_multiplier as group_image_rate_multiplier,
                       g.image_price_1k as group_image_price_1k,
                       g.image_price_2k as group_image_price_2k,
                       g.image_price_4k as group_image_price_4k,
                       g.claude_code_only as group_claude_code_only,
                       g.fallback_group_id as group_fallback_group_id,
                       g.fallback_group_id_on_invalid_request as group_fallback_group_id_on_invalid_request,
                       g.allow_messages_dispatch as group_allow_messages_dispatch,
                       g.default_mapped_model as group_default_mapped_model,
                       g.messages_dispatch_model_config::text as group_messages_dispatch_model_config,
                       g.require_oauth_only as group_require_oauth_only,
                       g.require_privacy_set as group_require_privacy_set,
                       g.supported_model_scopes::text as group_supported_model_scopes,
                       g.created_at as group_created_at,
                       g.updated_at as group_updated_at
                from api_keys k
                left join groups g on g.id = k.group_id and g.deleted_at is null
                where k.id = :id
                  and k.user_id = :userId
                  and k.deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId), (rs, rowNum) -> mapApiKey(rs));
        return rows.stream().findFirst();
    }

    public boolean existsActiveKey(String key) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                    from api_keys
                    where key = :key
                      and deleted_at is null
                )
                """, new MapSqlParameterSource("key", key), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public long create(CreateCommand command) {
        Long id = jdbcTemplate.query("""
                insert into api_keys (
                    user_id, key, name, group_id, status,
                    ip_whitelist, ip_blacklist,
                    quota, quota_used, expires_at,
                    rate_limit_5h, rate_limit_1d, rate_limit_7d,
                    usage_5h, usage_1d, usage_7d,
                    window_5h_start, window_1d_start, window_7d_start,
                    created_at, updated_at
                )
                values (
                    :userId, :key, :name, :groupId, :status,
                    cast(:ipWhitelist as jsonb), cast(:ipBlacklist as jsonb),
                    :quota, :quotaUsed, :expiresAt,
                    :rateLimit5h, :rateLimit1d, :rateLimit7d,
                    :usage5h, :usage1d, :usage7d,
                    :window5hStart, :window1dStart, :window7dStart,
                    now(), now()
                )
                returning id
                """, command.toParams(), rs -> rs.next() ? rs.getLong("id") : null);
        if (id == null) {
            throw new IllegalStateException("failed to create api key");
        }
        return id;
    }

    public boolean update(UpdateCommand command) {
        Integer updated = jdbcTemplate.query("""
                update api_keys
                set key = :key,
                    name = :name,
                    group_id = :groupId,
                    status = :status,
                    ip_whitelist = cast(:ipWhitelist as jsonb),
                    ip_blacklist = cast(:ipBlacklist as jsonb),
                    quota = :quota,
                    quota_used = :quotaUsed,
                    expires_at = :expiresAt,
                    rate_limit_5h = :rateLimit5h,
                    rate_limit_1d = :rateLimit1d,
                    rate_limit_7d = :rateLimit7d,
                    usage_5h = :usage5h,
                    usage_1d = :usage1d,
                    usage_7d = :usage7d,
                    window_5h_start = :window5hStart,
                    window_1d_start = :window1dStart,
                    window_7d_start = :window7dStart,
                    updated_at = now()
                where id = :id
                  and user_id = :userId
                  and deleted_at is null
                returning 1
                """, command.toParams(), (rs, rowNum) -> 1).stream().findFirst().orElse(null);
        return updated != null;
    }

    public boolean softDelete(long id, long userId, String tombstoneKey) {
        Integer updated = jdbcTemplate.query("""
                update api_keys
                set key = :tombstoneKey,
                    deleted_at = now(),
                    updated_at = now()
                where id = :id
                  and user_id = :userId
                  and deleted_at is null
                returning 1
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("tombstoneKey", tombstoneKey), (rs, rowNum) -> 1).stream().findFirst().orElse(null);
        return updated != null;
    }

    private UserApiKeyResponse mapApiKey(ResultSet rs) throws SQLException {
        Timestamp window5h = rs.getTimestamp("window_5h_start");
        Timestamp window1d = rs.getTimestamp("window_1d_start");
        Timestamp window7d = rs.getTimestamp("window_7d_start");
        String status = normalizeStatus(rs.getString("status"));
        String window5hStart = toIsoString(window5h);
        String window1dStart = toIsoString(window1d);
        String window7dStart = toIsoString(window7d);
        String reset5hAt = computeResetAt(window5h, 5);
        String reset1dAt = computeResetAt(window1d, 24);
        String reset7dAt = computeResetAt(window7d, 24 * 7);
        return new UserApiKeyResponse(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("key"),
                rs.getString("name"),
                rs.getObject("group_id", Long.class),
                status,
                jsonHelper.readStringList(rs.getString("ip_whitelist")),
                jsonHelper.readStringList(rs.getString("ip_blacklist")),
                toIsoString(rs.getTimestamp("last_used_at")),
                toDouble(rs.getBigDecimal("quota"), 0.0d),
                toDouble(rs.getBigDecimal("quota_used"), 0.0d),
                toIsoString(rs.getTimestamp("expires_at")),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at")),
                toDouble(rs.getBigDecimal("rate_limit_5h"), 0.0d),
                toDouble(rs.getBigDecimal("rate_limit_1d"), 0.0d),
                toDouble(rs.getBigDecimal("rate_limit_7d"), 0.0d),
                effectiveUsage(window5h, 5, toDouble(rs.getBigDecimal("usage_5h"), 0.0d)),
                effectiveUsage(window1d, 24, toDouble(rs.getBigDecimal("usage_1d"), 0.0d)),
                effectiveUsage(window7d, 24 * 7, toDouble(rs.getBigDecimal("usage_7d"), 0.0d)),
                window5hStart,
                window1dStart,
                window7dStart,
                reset5hAt,
                reset1dAt,
                reset7dAt,
                mapGroup(rs)
        );
    }

    private UserAvailableGroupResponse mapGroup(ResultSet rs) throws SQLException {
        Long groupId = rs.getObject("group_ref_id", Long.class);
        if (groupId == null) {
            return null;
        }
        return new UserAvailableGroupResponse(
                groupId,
                rs.getString("group_name"),
                rs.getString("group_description"),
                rs.getString("group_platform"),
                toDouble(rs.getBigDecimal("group_rate_multiplier"), 1.0d),
                rs.getInt("group_rpm_limit"),
                rs.getBoolean("group_is_exclusive"),
                rs.getString("group_status"),
                rs.getString("group_subscription_type"),
                toNullableDouble(rs.getBigDecimal("group_daily_limit_usd")),
                toNullableDouble(rs.getBigDecimal("group_weekly_limit_usd")),
                toNullableDouble(rs.getBigDecimal("group_monthly_limit_usd")),
                rs.getBoolean("group_allow_image_generation"),
                rs.getBoolean("group_image_rate_independent"),
                toDouble(rs.getBigDecimal("group_image_rate_multiplier"), 1.0d),
                toNullableDouble(rs.getBigDecimal("group_image_price_1k")),
                toNullableDouble(rs.getBigDecimal("group_image_price_2k")),
                toNullableDouble(rs.getBigDecimal("group_image_price_4k")),
                rs.getBoolean("group_claude_code_only"),
                rs.getObject("group_fallback_group_id", Long.class),
                rs.getObject("group_fallback_group_id_on_invalid_request", Long.class),
                rs.getBoolean("group_allow_messages_dispatch"),
                rs.getString("group_default_mapped_model"),
                jsonHelper.readObject(
                        rs.getString("group_messages_dispatch_model_config"),
                        UserAvailableGroupResponse.MessagesDispatchModelConfig.class
                ),
                rs.getBoolean("group_require_oauth_only"),
                rs.getBoolean("group_require_privacy_set"),
                jsonHelper.readStringList(rs.getString("group_supported_model_scopes")),
                toIsoString(rs.getTimestamp("group_created_at")),
                toIsoString(rs.getTimestamp("group_updated_at"))
        );
    }

    private MapSqlParameterSource baseParams(long userId, String normalizedSearch, String status, Long groupId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId);
        if (normalizedSearch != null) {
            params.addValue("likeSearch", "%" + normalizedSearch + "%");
        }
        String normalizedStatus = normalizeText(status);
        if (normalizedStatus != null) {
            params.addValue("status", normalizedStatus);
        }
        if (groupId != null) {
            params.addValue("groupId", groupId);
        }
        return params;
    }

    private String buildWhereClause(String normalizedSearch, String status, Long groupId) {
        StringBuilder where = new StringBuilder("""
                where k.user_id = :userId
                  and k.deleted_at is null
                """);
        if (normalizedSearch != null) {
            where.append("\n  and (k.name ilike :likeSearch or k.key ilike :likeSearch)");
        }
        String normalizedStatus = normalizeText(status);
        if (normalizedStatus != null) {
            switch (normalizedStatus) {
                case "inactive" -> where.append("""
                          and coalesce(nullif(k.status, ''), 'active') in ('inactive', 'disabled')
                        """);
                case "active", "quota_exhausted", "expired" -> where.append("""
                          and coalesce(nullif(k.status, ''), 'active') = :status
                        """);
                default -> where.append("""
                          and coalesce(nullif(k.status, ''), 'active') = :status
                        """);
            }
        }
        if (groupId != null) {
            if (groupId == 0) {
                where.append("""
                          and k.group_id is null
                        """);
            } else {
                where.append("""
                          and k.group_id = :groupId
                        """);
            }
        }
        return where.toString();
    }

    private String resolveSortBy(String sortBy) {
        return switch (normalizeText(sortBy) == null ? "" : normalizeText(sortBy)) {
            case "name" -> "k.name";
            case "expires_at" -> "k.expires_at";
            case "status" -> "k.status";
            case "last_used_at" -> "k.last_used_at";
            default -> "k.created_at";
        };
    }

    private String resolveSortOrder(String sortOrder) {
        return "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
    }

    private String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "active";
        }
        return "disabled".equalsIgnoreCase(raw) ? "inactive" : raw.trim().toLowerCase();
    }

    private double effectiveUsage(Timestamp windowStart, int hours, double usage) {
        if (windowStart == null) {
            return 0;
        }
        Instant resetAt = windowStart.toInstant().plus(hours, ChronoUnit.HOURS);
        return resetAt.isAfter(Instant.now()) ? usage : 0;
    }

    private String computeResetAt(Timestamp windowStart, int hours) {
        if (windowStart == null) {
            return null;
        }
        Instant resetAt = windowStart.toInstant().plus(hours, ChronoUnit.HOURS);
        return resetAt.isAfter(Instant.now()) ? resetAt.toString() : null;
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Double toNullableDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private double toDouble(BigDecimal value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    public record CreateCommand(
            long userId,
            String key,
            String name,
            Long groupId,
            String status,
            String ipWhitelist,
            String ipBlacklist,
            double quota,
            double quotaUsed,
            Timestamp expiresAt,
            double rateLimit5h,
            double rateLimit1d,
            double rateLimit7d,
            double usage5h,
            double usage1d,
            double usage7d,
            Timestamp window5hStart,
            Timestamp window1dStart,
            Timestamp window7dStart
    ) {
        MapSqlParameterSource toParams() {
            return new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("key", key)
                    .addValue("name", name)
                    .addValue("groupId", groupId)
                    .addValue("status", status)
                    .addValue("ipWhitelist", ipWhitelist)
                    .addValue("ipBlacklist", ipBlacklist)
                    .addValue("quota", quota)
                    .addValue("quotaUsed", quotaUsed)
                    .addValue("expiresAt", expiresAt)
                    .addValue("rateLimit5h", rateLimit5h)
                    .addValue("rateLimit1d", rateLimit1d)
                    .addValue("rateLimit7d", rateLimit7d)
                    .addValue("usage5h", usage5h)
                    .addValue("usage1d", usage1d)
                    .addValue("usage7d", usage7d)
                    .addValue("window5hStart", window5hStart)
                    .addValue("window1dStart", window1dStart)
                    .addValue("window7dStart", window7dStart);
        }
    }

    public record UpdateCommand(
            long id,
            long userId,
            String key,
            String name,
            Long groupId,
            String status,
            String ipWhitelist,
            String ipBlacklist,
            double quota,
            double quotaUsed,
            Timestamp expiresAt,
            double rateLimit5h,
            double rateLimit1d,
            double rateLimit7d,
            double usage5h,
            double usage1d,
            double usage7d,
            Timestamp window5hStart,
            Timestamp window1dStart,
            Timestamp window7dStart
    ) {
        MapSqlParameterSource toParams() {
            return new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("userId", userId)
                    .addValue("key", key)
                    .addValue("name", name)
                    .addValue("groupId", groupId)
                    .addValue("status", status)
                    .addValue("ipWhitelist", ipWhitelist)
                    .addValue("ipBlacklist", ipBlacklist)
                    .addValue("quota", quota)
                    .addValue("quotaUsed", quotaUsed)
                    .addValue("expiresAt", expiresAt)
                    .addValue("rateLimit5h", rateLimit5h)
                    .addValue("rateLimit1d", rateLimit1d)
                    .addValue("rateLimit7d", rateLimit7d)
                    .addValue("usage5h", usage5h)
                    .addValue("usage1d", usage1d)
                    .addValue("usage7d", usage7d)
                    .addValue("window5hStart", window5hStart)
                    .addValue("window1dStart", window1dStart)
                    .addValue("window7dStart", window7dStart);
        }
    }
}
