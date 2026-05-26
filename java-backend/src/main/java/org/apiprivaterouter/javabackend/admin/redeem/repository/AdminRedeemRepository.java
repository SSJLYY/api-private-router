package org.apiprivaterouter.javabackend.admin.redeem.repository;

import org.apiprivaterouter.javabackend.admin.redeem.model.AdminRedeemCodeResponse;
import org.apiprivaterouter.javabackend.admin.redeem.model.RedeemStatsResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AdminRedeemRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminRedeemRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResponse<AdminRedeemCodeResponse> listCodes(int page, int pageSize, String type, String status, String search, String sortBy, String sortOrder) {
        int offset = Math.max(page - 1, 0) * pageSize;
        String resolvedSortBy = switch (sortBy == null ? "" : sortBy.trim()) {
            case "code" -> "rc.code";
            case "type" -> "rc.type";
            case "value" -> "rc.value";
            case "status" -> "rc.status";
            case "used_at" -> "rc.used_at";
            default -> "rc.id";
        };
        String resolvedSortOrder = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
        StringBuilder where = new StringBuilder("where 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        String normalizedType = type == null || type.isBlank() ? null : type.trim();
        if (normalizedType != null) {
            where.append(" and rc.type = :type");
            params.addValue("type", normalizedType);
        }
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim();
        if (normalizedStatus != null) {
            where.append(" and rc.status = :status");
            params.addValue("status", normalizedStatus);
        }
        String normalizedSearch = search == null || search.isBlank() ? null : search.trim();
        if (normalizedSearch != null) {
            where.append(" and (rc.code ilike :likeSearch or coalesce(u.email, '') ilike :likeSearch)");
            params.addValue("likeSearch", "%" + normalizedSearch + "%");
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from redeem_codes rc
                left join users u on u.id = rc.used_by
                """ + where, params, Long.class);
        List<AdminRedeemCodeResponse> items = jdbcTemplate.query("""
                select rc.id, rc.code, rc.type, rc.value, rc.status, rc.used_by, rc.used_at, rc.created_at,
                       rc.group_id, rc.validity_days, coalesce(rc.notes, '') as notes,
                       u.email as user_email, u.username as user_username,
                       g.name as group_name, g.platform as group_platform, g.subscription_type
                from redeem_codes rc
                left join users u on u.id = rc.used_by
                left join groups g on g.id = rc.group_id
                """ + where + " order by " + resolvedSortBy + " " + resolvedSortOrder + ", rc.id desc limit :pageSize offset :offset",
                params, (rs, rowNum) -> mapRedeemCode(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public Optional<AdminRedeemCodeResponse> getCode(long id) {
        List<AdminRedeemCodeResponse> rows = jdbcTemplate.query("""
                select rc.id, rc.code, rc.type, rc.value, rc.status, rc.used_by, rc.used_at, rc.created_at,
                       rc.group_id, rc.validity_days, coalesce(rc.notes, '') as notes,
                       u.email as user_email, u.username as user_username,
                       g.name as group_name, g.platform as group_platform, g.subscription_type
                from redeem_codes rc
                left join users u on u.id = rc.used_by
                left join groups g on g.id = rc.group_id
                where rc.id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapRedeemCode(rs));
        return rows.stream().findFirst();
    }

    public long createCode(String code, String type, double value, String status, Long groupId, int validityDays) {
        Long id = jdbcTemplate.query("""
                insert into redeem_codes (code, type, value, status, group_id, validity_days, notes, created_at)
                values (:code, :type, :value, :status, :groupId, :validityDays, :notes, now())
                returning id
                """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("type", type)
                .addValue("value", value)
                .addValue("status", status)
                .addValue("groupId", groupId)
                .addValue("validityDays", validityDays)
                .addValue("notes", ""), rs -> rs.next() ? rs.getLong("id") : null);
        if (id == null) {
            throw new IllegalStateException("failed to create redeem code");
        }
        return id;
    }

    public long createCode(String code, String type, double value, String status, Long groupId, int validityDays, String notes) {
        Long id = jdbcTemplate.query("""
                insert into redeem_codes (code, type, value, status, group_id, validity_days, notes, created_at)
                values (:code, :type, :value, :status, :groupId, :validityDays, :notes, now())
                returning id
                """, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("type", type)
                .addValue("value", value)
                .addValue("status", status)
                .addValue("groupId", groupId)
                .addValue("validityDays", validityDays)
                .addValue("notes", notes == null ? "" : notes), rs -> rs.next() ? rs.getLong("id") : null);
        if (id == null) {
            throw new IllegalStateException("failed to create redeem code");
        }
        return id;
    }

    public Optional<AdminRedeemCodeResponse> findCodeByCode(String code) {
        List<AdminRedeemCodeResponse> rows = jdbcTemplate.query("""
                select rc.id, rc.code, rc.type, rc.value, rc.status, rc.used_by, rc.used_at, rc.created_at,
                       rc.group_id, rc.validity_days, coalesce(rc.notes, '') as notes,
                       u.email as user_email, u.username as user_username,
                       g.name as group_name, g.platform as group_platform, g.subscription_type
                from redeem_codes rc
                left join users u on u.id = rc.used_by
                left join groups g on g.id = rc.group_id
                where rc.code = :code
                limit 1
                """, new MapSqlParameterSource("code", code), (rs, rowNum) -> mapRedeemCode(rs));
        return rows.stream().findFirst();
    }

    public Optional<AdminRedeemCodeResponse> findCodeByCodeForUpdate(String code) {
        List<AdminRedeemCodeResponse> rows = jdbcTemplate.query("""
                select rc.id, rc.code, rc.type, rc.value, rc.status, rc.used_by, rc.used_at, rc.created_at,
                       rc.group_id, rc.validity_days, coalesce(rc.notes, '') as notes,
                       u.email as user_email, u.username as user_username,
                       g.name as group_name, g.platform as group_platform, g.subscription_type
                from redeem_codes rc
                left join users u on u.id = rc.used_by
                left join groups g on g.id = rc.group_id
                where rc.code = :code
                for update
                """, new MapSqlParameterSource("code", code), (rs, rowNum) -> mapRedeemCode(rs));
        return rows.stream().findFirst();
    }

    public boolean userExists(long userId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                    from users
                    where id = :userId and deleted_at is null
                )
                """, new MapSqlParameterSource("userId", userId), Boolean.class);
        return Boolean.TRUE.equals(exists);
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

    public Optional<SubscriptionSnapshot> findLatestSubscriptionForUpdate(long userId, long groupId) {
        List<SubscriptionSnapshot> rows = jdbcTemplate.query("""
                select id, expires_at, status, notes
                from user_subscriptions
                where user_id = :userId and group_id = :groupId and deleted_at is null
                order by created_at desc
                limit 1
                for update
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId), (rs, rowNum) -> new SubscriptionSnapshot(
                rs.getLong("id"),
                rs.getObject("expires_at", java.time.OffsetDateTime.class),
                rs.getString("status"),
                rs.getString("notes")
        ));
        return rows.stream().findFirst();
    }

    public long createSubscription(long userId, long groupId, java.time.OffsetDateTime startsAt, java.time.OffsetDateTime expiresAt, String notes) {
        Long id = jdbcTemplate.query("""
                insert into user_subscriptions (
                    user_id, group_id, starts_at, expires_at, status,
                    daily_usage_usd, weekly_usage_usd, monthly_usage_usd,
                    assigned_at, notes, created_at, updated_at
                ) values (
                    :userId, :groupId, :startsAt, :expiresAt, 'active',
                    0, 0, 0,
                    now(), :notes, now(), now()
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", groupId)
                .addValue("startsAt", startsAt)
                .addValue("expiresAt", expiresAt)
                .addValue("notes", notes == null ? "" : notes), rs -> rs.next() ? rs.getLong(1) : null);
        if (id == null) {
            throw new IllegalStateException("failed to create subscription");
        }
        return id;
    }

    public void updateSubscription(long id, java.time.OffsetDateTime expiresAt, String status, String notes) {
        jdbcTemplate.update("""
                update user_subscriptions
                set expires_at = :expiresAt,
                    status = :status,
                    notes = :notes,
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expiresAt", expiresAt)
                .addValue("status", status)
                .addValue("notes", notes == null ? "" : notes));
    }

    public int markCodeUsed(long id, long userId) {
        return jdbcTemplate.update("""
                update redeem_codes
                set status = 'used',
                    used_by = :userId,
                    used_at = now()
                where id = :id and status = 'unused'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId));
    }

    public void deleteCode(long id) {
        jdbcTemplate.update("delete from redeem_codes where id = :id", new MapSqlParameterSource("id", id));
    }

    public void updateStatus(long id, String status) {
        jdbcTemplate.update("""
                update redeem_codes
                set status = :status
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status));
    }

    public boolean existsSubscriptionGroup(long groupId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from groups
                where id = :id and subscription_type = 'subscription'
                """, new MapSqlParameterSource("id", groupId), Long.class);
        return count != null && count > 0;
    }

    public RedeemStatsResponse getStats() {
        Map<String, Long> byType = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select type, count(*) as total
                from redeem_codes
                group by type
                order by type asc
                """, new MapSqlParameterSource(), rs -> {
            byType.put(rs.getString("type"), rs.getLong("total"));
        });
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select status, count(*) as total
                from redeem_codes
                group by status
                """, new MapSqlParameterSource(), rs -> {
            statusCounts.put(rs.getString("status"), rs.getLong("total"));
        });
        Long totalCodes = jdbcTemplate.queryForObject("select count(*) from redeem_codes", new MapSqlParameterSource(), Long.class);
        Double totalValue = jdbcTemplate.queryForObject("""
                select coalesce(sum(case when value > 0 then value else 0 end), 0)
                from redeem_codes
                """, new MapSqlParameterSource(), Double.class);
        long unusedCount = statusCounts.getOrDefault("unused", 0L);
        return new RedeemStatsResponse(
                totalCodes == null ? 0 : totalCodes,
                unusedCount,
                statusCounts.getOrDefault("used", 0L),
                statusCounts.getOrDefault("expired", 0L),
                totalValue == null ? 0 : totalValue,
                byType
        );
    }

    public String exportCodesCsv(String type, String status, String search, String sortBy, String sortOrder) {
        PageResponse<AdminRedeemCodeResponse> page = listCodes(1, 10000, type, status, search, sortBy, sortOrder);
        StringBuilder csv = new StringBuilder();
        csv.append("id,code,type,value,status,used_by,used_by_email,used_at,created_at\n");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
        for (AdminRedeemCodeResponse code : page.items()) {
            csv.append(code.id()).append(',')
                    .append(csvEscape(code.code())).append(',')
                    .append(csvEscape(code.type())).append(',')
                    .append(code.value()).append(',')
                    .append(csvEscape(code.status())).append(',')
                    .append(code.used_by() == null ? "" : code.used_by()).append(',')
                    .append(csvEscape(code.user() == null ? "" : String.valueOf(code.user().getOrDefault("email", "")))).append(',')
                    .append(csvEscape(formatTimestamp(code.used_at(), formatter))).append(',')
                    .append(csvEscape(formatTimestamp(code.created_at(), formatter))).append('\n');
        }
        return csv.toString();
    }

    private AdminRedeemCodeResponse mapRedeemCode(ResultSet rs) throws SQLException {
        return new AdminRedeemCodeResponse(
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
                rs.getString("notes"),
                toUserMap(rs),
                toGroupMap(rs)
        );
    }

    private Map<String, Object> toUserMap(ResultSet rs) throws SQLException {
        Long usedBy = rs.getObject("used_by", Long.class);
        if (usedBy == null) {
            return null;
        }
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", usedBy);
        user.put("email", rs.getString("user_email"));
        user.put("username", rs.getString("user_username"));
        return user;
    }

    private Map<String, Object> toGroupMap(ResultSet rs) throws SQLException {
        Long groupId = rs.getObject("group_id", Long.class);
        if (groupId == null) {
            return null;
        }
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", groupId);
        group.put("name", rs.getString("group_name"));
        group.put("platform", rs.getString("group_platform"));
        group.put("subscription_type", rs.getString("subscription_type"));
        return group;
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private String formatTimestamp(String raw, DateTimeFormatter formatter) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return formatter.format(Timestamp.from(java.time.Instant.parse(raw)).toInstant());
    }

    private String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    public record SubscriptionSnapshot(
            long id,
            java.time.OffsetDateTime expiresAt,
            String status,
            String notes
    ) {
    }
}
