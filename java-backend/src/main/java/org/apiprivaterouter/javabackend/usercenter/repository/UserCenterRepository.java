package org.apiprivaterouter.javabackend.usercenter.repository;

import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.usernotifyemail.model.NotifyEmailCodec;
import org.apiprivaterouter.javabackend.usercenter.model.AffiliateDetailResponse;
import org.apiprivaterouter.javabackend.usercenter.model.NotifyEmailEntry;
import org.apiprivaterouter.javabackend.usercenter.model.UpdateProfileRequest;
import org.apiprivaterouter.javabackend.usercenter.model.UserProfileResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class UserCenterRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public UserCenterRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public Optional<UserProfileResponse> findProfileById(long userId) {
        String sql = """
                select u.id, u.email, u.username, u.role, u.status, u.balance, u.concurrency, u.rpm_limit,
                       u.signup_source, u.last_active_at, u.created_at, u.updated_at,
                       balance_notify_enabled, balance_notify_threshold, balance_notify_extra_emails
                from users u
                where u.id = :userId and u.deleted_at is null
                """;
        List<UserProfileResponse> rows = jdbcTemplate.query(sql, new MapSqlParameterSource("userId", userId), (rs, rowNum) ->
                new UserProfileResponse(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("username"),
                        getUserAttribute(userId, "avatar_url"),
                        rs.getString("role"),
                        rs.getString("status"),
                        rs.getDouble("balance"),
                        rs.getInt("concurrency"),
                        rs.getObject("rpm_limit", Integer.class),
                        getAllowedGroupIds(userId),
                        rs.getBoolean("balance_notify_enabled"),
                        toDouble(rs.getBigDecimal("balance_notify_threshold")),
                        parseNotifyEmails(rs.getString("balance_notify_extra_emails")),
                        List.of("email")
                        ,
                        rs.getString("signup_source"),
                        toIsoString(rs.getTimestamp("last_active_at")),
                        toIsoString(rs.getTimestamp("created_at")),
                        toIsoString(rs.getTimestamp("updated_at"))
                ));
        return rows.stream().findFirst();
    }

    public void updateProfile(long userId, UpdateProfileRequest request) {
        String sql = """
                update users
                set username = coalesce(:username, username),
                    balance_notify_enabled = coalesce(:balanceNotifyEnabled, balance_notify_enabled),
                    balance_notify_threshold = coalesce(:balanceNotifyThreshold, balance_notify_threshold),
                    balance_notify_extra_emails = coalesce(:balanceNotifyExtraEmails, balance_notify_extra_emails),
                    updated_at = now()
                where id = :userId and deleted_at is null
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("username", request.username())
                .addValue("balanceNotifyEnabled", request.balance_notify_enabled())
                .addValue("balanceNotifyThreshold", request.balance_notify_threshold())
                .addValue("balanceNotifyExtraEmails", request.balance_notify_extra_emails() == null ? null : toJson(request.balance_notify_extra_emails()));
        jdbcTemplate.update(sql, params);
        if (request.avatar_url() != null) {
            upsertUserAttribute(userId, "avatar_url", request.avatar_url().trim());
        }
    }

    public String getPasswordHash(long userId) {
        List<String> rows = jdbcTemplate.query("""
                select password_hash
                from users
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> rs.getString("password_hash"));
        return rows.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    public void updatePassword(long userId, String passwordHash) {
        jdbcTemplate.update("""
                update users
                set password_hash = :passwordHash, updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("passwordHash", passwordHash));
    }

    public double transferAffiliateQuota(long userId) {
        Double quota = jdbcTemplate.queryForObject("""
                select aff_quota
                from user_affiliates
                where user_id = :userId
                """, new MapSqlParameterSource("userId", userId), Double.class);
        double transferable = quota == null ? 0.0 : quota;
        if (transferable <= 0) {
            return 0.0;
        }
        jdbcTemplate.update("""
                update users
                set balance = balance + :amount, updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("amount", transferable));
        jdbcTemplate.update("""
                update user_affiliates
                set aff_quota = 0, aff_history_quota = aff_history_quota + :amount, updated_at = now()
                where user_id = :userId
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("amount", transferable));
        return transferable;
    }

    public Optional<AffiliateDetailResponse> findAffiliateDetail(long userId) {
        String sql = """
                select aff_code, aff_quota, aff_history_quota, aff_count
                from user_affiliates
                where user_id = :userId
                """;
        List<AffiliateDetailResponse> rows = jdbcTemplate.query(sql, new MapSqlParameterSource("userId", userId), (rs, rowNum) ->
                new AffiliateDetailResponse(
                        rs.getString("aff_code"),
                        rs.getDouble("aff_quota"),
                        rs.getDouble("aff_history_quota"),
                        rs.getInt("aff_count")
                ));
        return rows.stream().findFirst();
    }

    private List<Long> getAllowedGroupIds(long userId) {
        return jdbcTemplate.query("""
                select group_id
                from user_allowed_groups
                where user_id = :userId
                order by group_id asc
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> rs.getLong("group_id"));
    }

    private String getUserAttribute(long userId, String attributeKey) {
        String sql = """
                select uav.value
                from user_attribute_values uav
                join user_attribute_definitions uad on uad.id = uav.attribute_id
                where uav.user_id = :userId and uad.key = :attributeKey
                limit 1
                """;
        List<String> rows = jdbcTemplate.query(sql,
                new MapSqlParameterSource().addValue("userId", userId).addValue("attributeKey", attributeKey),
                (rs, rowNum) -> rs.getString("value"));
        return rows.stream().findFirst().orElse(null);
    }

    private void upsertUserAttribute(long userId, String attributeKey, String value) {
        Long attributeId = jdbcTemplate.query("""
                select id
                from user_attribute_definitions
                where key = :attributeKey
                limit 1
                """, new MapSqlParameterSource("attributeKey", attributeKey), rs -> rs.next() ? rs.getLong("id") : null);
        if (attributeId == null) {
            return;
        }
        jdbcTemplate.update("""
                insert into user_attribute_values (user_id, attribute_id, value, created_at, updated_at)
                values (:userId, :attributeId, :value, now(), now())
                on conflict (user_id, attribute_id)
                do update set value = excluded.value, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("attributeId", attributeId)
                .addValue("value", value));
    }

    private List<NotifyEmailEntry> parseNotifyEmails(String raw) {
        return NotifyEmailCodec.parse(jsonHelper, raw);
    }

    private String toJson(List<NotifyEmailEntry> entries) {
        return jsonHelper.writeJson(entries);
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
