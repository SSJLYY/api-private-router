package org.apiprivaterouter.javabackend.admin.apikey.repository;

import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.usergroups.model.UserAvailableGroupResponse;
import org.apiprivaterouter.javabackend.userkeys.model.UserApiKeyResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Repository
public class AdminApiKeyRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AdminApiKeyRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public Optional<ApiKeyAdminRow> findById(long id) {
        List<ApiKeyAdminRow> rows = jdbcTemplate.query(baseSql() + """
                where k.id = :id and k.deleted_at is null
                limit 1
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapAdminRow(rs));
        return rows.stream().findFirst();
    }

    public Optional<ApiKeyAdminRow> findByIdForUpdate(long id) {
        List<ApiKeyAdminRow> rows = jdbcTemplate.query(baseSql() + """
                where k.id = :id and k.deleted_at is null
                for update
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapAdminRow(rs));
        return rows.stream().findFirst();
    }

    public void updateGroupId(long id, Long groupId) {
        jdbcTemplate.update("""
                update api_keys
                set group_id = :groupId,
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("groupId", groupId));
    }

    public void resetRateLimitUsage(long id) {
        jdbcTemplate.update("""
                update api_keys
                set usage_5h = 0,
                    usage_1d = 0,
                    usage_7d = 0,
                    window_5h_start = null,
                    window_1d_start = null,
                    window_7d_start = null,
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
    }

    public Optional<GroupRow> findGroupById(long id) {
        List<GroupRow> rows = jdbcTemplate.query("""
                select id, name, status, platform, is_exclusive, subscription_type
                from groups
                where id = :id and deleted_at is null
                limit 1
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> new GroupRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("platform"),
                rs.getBoolean("is_exclusive"),
                rs.getString("subscription_type")
        ));
        return rows.stream().findFirst();
    }

    public boolean hasAllowedGroup(long userId, long groupId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                    from user_allowed_groups
                    where user_id = :userId and group_id = :groupId
                )
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public void addAllowedGroup(long userId, long groupId) {
        jdbcTemplate.update("""
                insert into user_allowed_groups (user_id, group_id, created_at)
                values (:userId, :groupId, now())
                on conflict do nothing
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId));
    }

    public boolean hasActiveSubscription(long userId, long groupId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                    from user_subscriptions
                    where user_id = :userId
                      and group_id = :groupId
                      and deleted_at is null
                      and status = 'active'
                      and expires_at > now()
                )
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public UserApiKeyResponse toResponse(ApiKeyAdminRow row) {
        return row.response();
    }

    private String baseSql() {
        return """
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
                """;
    }

    private ApiKeyAdminRow mapAdminRow(ResultSet rs) throws SQLException {
        return new ApiKeyAdminRow(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("key"),
                mapApiKey(rs)
        );
    }

    private UserApiKeyResponse mapApiKey(ResultSet rs) throws SQLException {
        Timestamp window5h = rs.getTimestamp("window_5h_start");
        Timestamp window1d = rs.getTimestamp("window_1d_start");
        Timestamp window7d = rs.getTimestamp("window_7d_start");
        return new UserApiKeyResponse(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("key"),
                rs.getString("name"),
                rs.getObject("group_id", Long.class),
                normalizeStatus(rs.getString("status")),
                jsonHelper.readStringList(rs.getString("ip_whitelist")),
                jsonHelper.readStringList(rs.getString("ip_blacklist")),
                toIsoString(rs.getTimestamp("last_used_at")),
                rs.getDouble("quota"),
                rs.getDouble("quota_used"),
                toIsoString(rs.getTimestamp("expires_at")),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at")),
                rs.getDouble("rate_limit_5h"),
                rs.getDouble("rate_limit_1d"),
                rs.getDouble("rate_limit_7d"),
                effectiveUsage(window5h, 5, rs.getDouble("usage_5h")),
                effectiveUsage(window1d, 24, rs.getDouble("usage_1d")),
                effectiveUsage(window7d, 24 * 7, rs.getDouble("usage_7d")),
                toIsoString(window5h),
                toIsoString(window1d),
                toIsoString(window7d),
                computeResetAt(window5h, 5),
                computeResetAt(window1d, 24),
                computeResetAt(window7d, 24 * 7),
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
                rs.getDouble("group_rate_multiplier"),
                rs.getInt("group_rpm_limit"),
                rs.getBoolean("group_is_exclusive"),
                rs.getString("group_status"),
                rs.getString("group_subscription_type"),
                rs.getObject("group_daily_limit_usd", Double.class),
                rs.getObject("group_weekly_limit_usd", Double.class),
                rs.getObject("group_monthly_limit_usd", Double.class),
                rs.getBoolean("group_allow_image_generation"),
                rs.getBoolean("group_image_rate_independent"),
                rs.getDouble("group_image_rate_multiplier"),
                rs.getObject("group_image_price_1k", Double.class),
                rs.getObject("group_image_price_2k", Double.class),
                rs.getObject("group_image_price_4k", Double.class),
                rs.getBoolean("group_claude_code_only"),
                rs.getObject("group_fallback_group_id", Long.class),
                rs.getObject("group_fallback_group_id_on_invalid_request", Long.class),
                rs.getBoolean("group_allow_messages_dispatch"),
                rs.getString("group_default_mapped_model"),
                jsonHelper.readObject(rs.getString("group_messages_dispatch_model_config"), UserAvailableGroupResponse.MessagesDispatchModelConfig.class),
                rs.getBoolean("group_require_oauth_only"),
                rs.getBoolean("group_require_privacy_set"),
                jsonHelper.readStringList(rs.getString("group_supported_model_scopes")),
                toIsoString(rs.getTimestamp("group_created_at")),
                toIsoString(rs.getTimestamp("group_updated_at"))
        );
    }

    private String normalizeStatus(String status) {
        return status == null || status.isBlank() ? "active" : status;
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

    public record ApiKeyAdminRow(
            long id,
            long userId,
            String key,
            UserApiKeyResponse response
    ) {
    }

    public record GroupRow(
            long id,
            String name,
            String status,
            String platform,
            boolean isExclusive,
            String subscriptionType
    ) {
        public boolean isSubscriptionGroup() {
            return "subscription".equalsIgnoreCase(subscriptionType);
        }
    }
}
