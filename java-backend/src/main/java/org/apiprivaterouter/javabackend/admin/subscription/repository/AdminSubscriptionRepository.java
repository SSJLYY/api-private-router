package org.apiprivaterouter.javabackend.admin.subscription.repository;

import org.apiprivaterouter.javabackend.admin.subscription.model.AdminSubscriptionResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AdminSubscriptionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminSubscriptionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResponse<AdminSubscriptionResponse> listSubscriptions(
            int page,
            int pageSize,
            Long userId,
            Long groupId,
            String status,
            String platform,
            String sortBy,
            String sortOrder
    ) {
        int offset = Math.max(page - 1, 0) * pageSize;
        String baseFrom = """
                from user_subscriptions us
                left join users u on u.id = us.user_id
                left join groups g on g.id = us.group_id
                left join users au on au.id = us.assigned_by
                """;
        String where = buildWhereClause(userId, groupId, status, platform);
        String orderBy = buildOrderBy(sortBy, sortOrder);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        applyListFilters(params, userId, groupId, status, platform);
        Long total = jdbcTemplate.queryForObject("select count(*) " + baseFrom + where, params, Long.class);
        List<AdminSubscriptionResponse> items = jdbcTemplate.query("""
                select us.id, us.user_id, us.group_id, us.starts_at, us.expires_at, us.status,
                       us.daily_usage_usd, us.weekly_usage_usd, us.monthly_usage_usd,
                       us.daily_window_start, us.weekly_window_start, us.monthly_window_start,
                       us.assigned_by, us.assigned_at, us.notes, us.created_at, us.updated_at, us.deleted_at,
                       u.email as user_email, u.username as user_username,
                       g.name as group_name, g.description as group_description, g.platform as group_platform,
                       g.rate_multiplier, g.status as group_status, g.subscription_type,
                       g.daily_limit_usd, g.weekly_limit_usd, g.monthly_limit_usd,
                       au.email as assigned_by_email, au.username as assigned_by_username
                """ + baseFrom + where + orderBy + """
                limit :pageSize offset :offset
                """, params, (rs, rowNum) -> mapSubscription(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public Optional<AdminSubscriptionResponse> getSubscription(long id) {
        List<AdminSubscriptionResponse> rows = jdbcTemplate.query("""
                select us.id, us.user_id, us.group_id, us.starts_at, us.expires_at, us.status,
                       us.daily_usage_usd, us.weekly_usage_usd, us.monthly_usage_usd,
                       us.daily_window_start, us.weekly_window_start, us.monthly_window_start,
                       us.assigned_by, us.assigned_at, us.notes, us.created_at, us.updated_at, us.deleted_at,
                       u.email as user_email, u.username as user_username,
                       g.name as group_name, g.description as group_description, g.platform as group_platform,
                       g.rate_multiplier, g.status as group_status, g.subscription_type,
                       g.daily_limit_usd, g.weekly_limit_usd, g.monthly_limit_usd,
                       au.email as assigned_by_email, au.username as assigned_by_username
                from user_subscriptions us
                left join users u on u.id = us.user_id
                left join groups g on g.id = us.group_id
                left join users au on au.id = us.assigned_by
                where us.id = :id and us.deleted_at is null
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapSubscription(rs));
        return rows.stream().findFirst();
    }

    public Optional<SubscriptionSnapshot> getSubscriptionSnapshot(long id) {
        List<SubscriptionSnapshot> rows = jdbcTemplate.query("""
                select id, user_id, group_id, starts_at, expires_at, status, notes
                from user_subscriptions
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> new SubscriptionSnapshot(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("group_id"),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("status"),
                defaultString(rs.getString("notes"))
        ));
        return rows.stream().findFirst();
    }

    public Optional<SubscriptionSnapshot> findExistingByUserAndGroup(long userId, long groupId) {
        List<SubscriptionSnapshot> rows = jdbcTemplate.query("""
                select id, user_id, group_id, starts_at, expires_at, status, notes
                from user_subscriptions
                where user_id = :userId and group_id = :groupId and deleted_at is null
                order by created_at desc
                limit 1
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId), (rs, rowNum) -> new SubscriptionSnapshot(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("group_id"),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("status"),
                defaultString(rs.getString("notes"))
        ));
        return rows.stream().findFirst();
    }

    public boolean userExists(long userId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1 from users
                    where id = :userId and deleted_at is null
                )
                """, new MapSqlParameterSource("userId", userId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public boolean subscriptionGroupExists(long groupId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1 from groups
                    where id = :groupId
                      and deleted_at is null
                      and subscription_type = 'subscription'
                )
                """, new MapSqlParameterSource("groupId", groupId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public long createSubscription(
            long userId,
            long groupId,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            String status,
            Long assignedBy,
            OffsetDateTime assignedAt,
            String notes
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into user_subscriptions (
                    user_id, group_id, starts_at, expires_at, status,
                    daily_usage_usd, weekly_usage_usd, monthly_usage_usd,
                    assigned_by, assigned_at, notes, created_at, updated_at
                ) values (
                    :userId, :groupId, :startsAt, :expiresAt, :status,
                    0, 0, 0,
                    :assignedBy, :assignedAt, :notes, now(), now()
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId)
                .addValue("startsAt", startsAt)
                .addValue("expiresAt", expiresAt)
                .addValue("status", status)
                .addValue("assignedBy", assignedBy)
                .addValue("assignedAt", assignedAt)
                .addValue("notes", notes), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to create subscription");
        }
        return key.longValue();
    }

    public void updateExpiresAt(long id, OffsetDateTime expiresAt) {
        jdbcTemplate.update("""
                update user_subscriptions
                set expires_at = :expiresAt, updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expiresAt", expiresAt));
    }

    public void updateStatus(long id, String status) {
        jdbcTemplate.update("""
                update user_subscriptions
                set status = :status, updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status));
    }

    public void revokeSubscription(long id) {
        jdbcTemplate.update("""
                update user_subscriptions
                set deleted_at = now(), updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
    }

    public void resetQuota(long id, boolean daily, boolean weekly, boolean monthly, OffsetDateTime windowStart) {
        if (daily) {
            jdbcTemplate.update("""
                    update user_subscriptions
                    set daily_usage_usd = 0, daily_window_start = :windowStart, updated_at = now()
                    where id = :id and deleted_at is null
                    """, new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("windowStart", windowStart));
        }
        if (weekly) {
            jdbcTemplate.update("""
                    update user_subscriptions
                    set weekly_usage_usd = 0, weekly_window_start = :windowStart, updated_at = now()
                    where id = :id and deleted_at is null
                    """, new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("windowStart", windowStart));
        }
        if (monthly) {
            jdbcTemplate.update("""
                    update user_subscriptions
                    set monthly_usage_usd = 0, monthly_window_start = :windowStart, updated_at = now()
                    where id = :id and deleted_at is null
                    """, new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("windowStart", windowStart));
        }
    }

    private void applyListFilters(MapSqlParameterSource params, Long userId, Long groupId, String status, String platform) {
        if (userId != null && userId > 0) {
            params.addValue("userId", userId);
        }
        if (groupId != null && groupId > 0) {
            params.addValue("groupId", groupId);
        }
        String normalizedPlatform = blankToNull(platform);
        if (normalizedPlatform != null) {
            params.addValue("platform", normalizedPlatform);
        }
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null && !List.of("active", "expired", "revoked").contains(normalizedStatus)) {
            params.addValue("status", normalizedStatus);
        }
    }

    private String buildWhereClause(Long userId, Long groupId, String status, String platform) {
        StringBuilder where = new StringBuilder("""
                where 1 = 1
                """);
        if (userId != null && userId > 0) {
            where.append("\n  and us.user_id = :userId");
        }
        if (groupId != null && groupId > 0) {
            where.append("\n  and us.group_id = :groupId");
        }
        if (blankToNull(platform) != null) {
            where.append("\n  and g.platform = :platform");
        }
        if (status == null || status.isBlank()) {
            where.append("\n  and us.deleted_at is null");
            return where.toString();
        }
        switch (status) {
            case "active" -> where.append("""
                    
                      and us.deleted_at is null
                      and us.status = 'active'
                      and us.expires_at > now()
                    """);
            case "expired" -> where.append("""
                    
                      and us.deleted_at is null
                      and (
                          us.status = 'expired'
                          or (us.status = 'active' and us.expires_at <= now())
                      )
                    """);
            case "revoked" -> where.append("\n  and us.deleted_at is not null");
            default -> where.append("""
                    
                      and us.deleted_at is null
                      and us.status = :status
                    """);
        }
        return where.toString();
    }

    private String buildOrderBy(String sortBy, String sortOrder) {
        String direction = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
        String field = switch (sortBy == null ? "" : sortBy.trim()) {
            case "expires_at" -> "us.expires_at";
            case "status" -> "case when us.deleted_at is not null then 'revoked' " +
                    "when us.status = 'active' and us.expires_at <= now() then 'expired' else us.status end";
            default -> "us.created_at";
        };
        return "\norder by " + field + " " + direction + ", us.id desc\n";
    }

    private AdminSubscriptionResponse mapSubscription(ResultSet rs) throws SQLException {
        Long assignedBy = rs.getObject("assigned_by", Long.class);
        AdminSubscriptionResponse.UserSummary user = new AdminSubscriptionResponse.UserSummary(
                rs.getLong("user_id"),
                defaultString(rs.getString("user_email")),
                defaultString(rs.getString("user_username"))
        );
        AdminSubscriptionResponse.GroupSummary group = new AdminSubscriptionResponse.GroupSummary(
                rs.getLong("group_id"),
                defaultString(rs.getString("group_name")),
                rs.getString("group_description"),
                defaultString(rs.getString("group_platform")),
                toNullableDouble(rs.getBigDecimal("rate_multiplier")),
                defaultString(rs.getString("group_status")),
                defaultString(rs.getString("subscription_type")),
                toNullableDouble(rs.getBigDecimal("daily_limit_usd")),
                toNullableDouble(rs.getBigDecimal("weekly_limit_usd")),
                toNullableDouble(rs.getBigDecimal("monthly_limit_usd"))
        );
        AdminSubscriptionResponse.UserSummary assignedByUser = assignedBy == null ? null : new AdminSubscriptionResponse.UserSummary(
                assignedBy,
                defaultString(rs.getString("assigned_by_email")),
                defaultString(rs.getString("assigned_by_username"))
        );
        return new AdminSubscriptionResponse(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("group_id"),
                toIsoString(rs.getTimestamp("starts_at")),
                toIsoString(rs.getTimestamp("expires_at")),
                resolveVisibleStatus(rs),
                toDouble(rs.getBigDecimal("daily_usage_usd"), 0.0d),
                toDouble(rs.getBigDecimal("weekly_usage_usd"), 0.0d),
                toDouble(rs.getBigDecimal("monthly_usage_usd"), 0.0d),
                toIsoString(rs.getTimestamp("daily_window_start")),
                toIsoString(rs.getTimestamp("weekly_window_start")),
                toIsoString(rs.getTimestamp("monthly_window_start")),
                assignedBy,
                toIsoString(rs.getTimestamp("assigned_at")),
                defaultString(rs.getString("notes")),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at")),
                user,
                group,
                assignedByUser
        );
    }

    private String resolveVisibleStatus(ResultSet rs) throws SQLException {
        Timestamp deletedAt = rs.getTimestamp("deleted_at");
        if (deletedAt != null) {
            return "revoked";
        }
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        String status = defaultString(rs.getString("status"));
        if ("active".equals(status) && expiresAt != null && expiresAt.toInstant().isBefore(java.time.Instant.now())) {
            return "expired";
        }
        return status;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Double toNullableDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private double toDouble(BigDecimal value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    public record SubscriptionSnapshot(
            long id,
            long userId,
            long groupId,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            String status,
            String notes
    ) {
    }
}
