package org.apiprivaterouter.javabackend.userredeem.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.apiprivaterouter.javabackend.userredeem.model.UserRedeemHistoryItemResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRedeemRepository {

    public static final String STATUS_UNUSED = "unused";
    public static final String STATUS_USED = "used";
    public static final String TYPE_BALANCE = "balance";
    public static final String TYPE_CONCURRENCY = "concurrency";
    public static final String TYPE_SUBSCRIPTION = "subscription";
    private static final String TYPE_ADMIN_BALANCE = "admin_balance";
    private static final String TYPE_ADMIN_CONCURRENCY = "admin_concurrency";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public UserRedeemRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<UserRedeemHistoryItemResponse> listHistory(long userId, int limit) {
        int normalizedLimit = limit <= 0 ? 25 : limit;
        return jdbcTemplate.query("""
                select rc.id, rc.code, rc.type, rc.value, rc.status, rc.used_by, rc.used_at, rc.created_at,
                       rc.group_id, coalesce(rc.validity_days, 0) as validity_days, rc.notes,
                       g.id as resolved_group_id, g.name as group_name
                from redeem_codes rc
                left join groups g on g.id = rc.group_id
                where rc.used_by = :userId
                order by rc.used_at desc
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", normalizedLimit), (rs, rowNum) -> mapHistoryItem(rs));
    }

    public Optional<RedeemCodeRecord> findByCodeForUpdate(String code) {
        List<RedeemCodeRecord> rows = jdbcTemplate.query("""
                select rc.id, rc.code, rc.type, rc.value, rc.status, rc.used_by, rc.used_at, rc.created_at,
                       rc.group_id, coalesce(rc.validity_days, 0) as validity_days, rc.notes,
                       g.name as group_name, g.subscription_type
                from redeem_codes rc
                left join groups g on g.id = rc.group_id
                where rc.code = :code
                for update
                """, new MapSqlParameterSource("code", code), (rs, rowNum) -> mapRedeemCodeRecord(rs));
        return rows.stream().findFirst();
    }

    public int markUsed(long id, long userId) {
        return jdbcTemplate.update("""
                update redeem_codes
                set status = :status,
                    used_by = :userId,
                    used_at = now()
                where id = :id and status = :expectedStatus
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("status", STATUS_USED)
                .addValue("expectedStatus", STATUS_UNUSED));
    }

    public Optional<UserAccountSnapshot> findUserById(long userId) {
        List<UserAccountSnapshot> rows = jdbcTemplate.query("""
                select id, balance, concurrency
                from users
                where id = :userId and deleted_at is null
                for update
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> new UserAccountSnapshot(
                rs.getLong("id"),
                rs.getDouble("balance"),
                rs.getInt("concurrency")
        ));
        return rows.stream().findFirst();
    }

    public void addBalance(long userId, double amount) {
        jdbcTemplate.update("""
                update users
                set balance = balance + :amount,
                    updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("amount", amount));
    }

    public void addConcurrency(long userId, int delta) {
        jdbcTemplate.update("""
                update users
                set concurrency = concurrency + :delta,
                    updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("delta", delta));
    }

    public Optional<SubscriptionRecord> findLatestSubscriptionForUpdate(long userId, long groupId) {
        List<SubscriptionRecord> rows = jdbcTemplate.query("""
                select us.id, us.user_id, us.group_id, us.starts_at, us.expires_at, us.status, us.notes
                from user_subscriptions us
                where us.user_id = :userId and us.group_id = :groupId and us.deleted_at is null
                order by us.created_at desc
                limit 1
                for update
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId), (rs, rowNum) -> new SubscriptionRecord(
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

    public long createSubscription(
            long userId,
            long groupId,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            String status,
            String notes
    ) {
        Long id = jdbcTemplate.query("""
                insert into user_subscriptions (
                    user_id, group_id, starts_at, expires_at, status,
                    daily_usage_usd, weekly_usage_usd, monthly_usage_usd,
                    assigned_at, notes, created_at, updated_at
                ) values (
                    :userId, :groupId, :startsAt, :expiresAt, :status,
                    0, 0, 0,
                    now(), :notes, now(), now()
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId)
                .addValue("startsAt", startsAt)
                .addValue("expiresAt", expiresAt)
                .addValue("status", status)
                .addValue("notes", notes), rs -> rs.next() ? rs.getLong("id") : null);
        if (id == null) {
            throw new IllegalStateException("failed to create subscription");
        }
        return id;
    }

    public void updateSubscriptionExpiry(long id, OffsetDateTime expiresAt) {
        jdbcTemplate.update("""
                update user_subscriptions
                set expires_at = :expiresAt,
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expiresAt", expiresAt));
    }

    public void updateSubscriptionStatus(long id, String status) {
        jdbcTemplate.update("""
                update user_subscriptions
                set status = :status,
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status));
    }

    public void updateSubscriptionNotes(long id, String notes) {
        jdbcTemplate.update("""
                update user_subscriptions
                set notes = :notes,
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("notes", notes));
    }

    private UserRedeemHistoryItemResponse mapHistoryItem(ResultSet rs) throws SQLException {
        return new UserRedeemHistoryItemResponse(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("type"),
                rs.getDouble("value"),
                rs.getString("status"),
                rs.getObject("used_by", Long.class),
                toIsoString(rs.getTimestamp("used_at")),
                toIsoString(rs.getTimestamp("created_at")),
                rs.getObject("group_id", Long.class),
                rs.getInt("validity_days"),
                resolveVisibleNotes(rs.getString("type"), rs.getString("notes")),
                mapGroup(rs)
        );
    }

    private UserRedeemHistoryItemResponse.GroupSummary mapGroup(ResultSet rs) throws SQLException {
        Long groupId = rs.getObject("resolved_group_id", Long.class);
        if (groupId == null) {
            return null;
        }
        return new UserRedeemHistoryItemResponse.GroupSummary(
                groupId,
                rs.getString("group_name")
        );
    }

    private RedeemCodeRecord mapRedeemCodeRecord(ResultSet rs) throws SQLException {
        return new RedeemCodeRecord(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("type"),
                rs.getDouble("value"),
                rs.getString("status"),
                rs.getObject("used_by", Long.class),
                toIsoString(rs.getTimestamp("used_at")),
                toIsoString(rs.getTimestamp("created_at")),
                rs.getObject("group_id", Long.class),
                rs.getInt("validity_days"),
                defaultString(rs.getString("notes")),
                rs.getString("group_name"),
                rs.getString("subscription_type")
        );
    }

    private String resolveVisibleNotes(String type, String notes) {
        if ((!TYPE_ADMIN_BALANCE.equals(type) && !TYPE_ADMIN_CONCURRENCY.equals(type))
                || notes == null
                || notes.isBlank()) {
            return null;
        }
        return notes;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    public record RedeemCodeRecord(
            long id,
            String code,
            String type,
            double value,
            String status,
            Long usedBy,
            String usedAt,
            String createdAt,
            Long groupId,
            int validityDays,
            String notes,
            String groupName,
            String subscriptionType
    ) {
    }

    public record UserAccountSnapshot(
            long id,
            double balance,
            int concurrency
    ) {
    }

    public record SubscriptionRecord(
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
