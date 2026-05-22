package org.apiprivaterouter.javabackend.usernotifyemail.repository;

import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.usercenter.model.NotifyEmailEntry;
import org.apiprivaterouter.javabackend.usernotifyemail.model.NotifyEmailCodeSession;
import org.apiprivaterouter.javabackend.usernotifyemail.model.NotifyEmailCodec;
import org.apiprivaterouter.javabackend.usernotifyemail.model.NotifyEmailRateLimitSession;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class UserNotifyEmailRepository {

    private static final String CODE_KEY_PREFIX = "java:user-notify-email:code:";
    private static final String RATE_LIMIT_KEY_PREFIX = "java:user-notify-email:rate:";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public UserNotifyEmailRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public Optional<NotifyEmailUserRow> findUserById(long userId) {
        List<NotifyEmailUserRow> rows = jdbcTemplate.query("""
                select id, balance_notify_extra_emails
                from users
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> new NotifyEmailUserRow(
                rs.getLong("id"),
                NotifyEmailCodec.parse(jsonHelper, rs.getString("balance_notify_extra_emails"))
        ));
        return rows.stream().findFirst();
    }

    public void updateNotifyEmails(long userId, List<NotifyEmailEntry> entries) {
        jdbcTemplate.update("""
                update users
                set balance_notify_extra_emails = :balanceNotifyExtraEmails,
                    updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("balanceNotifyExtraEmails", jsonHelper.writeJson(entries == null ? List.of() : entries)));
    }

    public NotifyEmailCodeSession findCodeSession(String email) {
        String key = codeKey(email);
        NotifyEmailCodeSession session = jsonHelper.readObject(getSettingValue(key), NotifyEmailCodeSession.class);
        if (session == null) {
            return null;
        }
        if (session.expiresAt() == null || session.expiresAt().isBefore(Instant.now())) {
            deleteSetting(key);
            return null;
        }
        return session;
    }

    public void saveCodeSession(String email, NotifyEmailCodeSession session) {
        upsertSetting(codeKey(email), jsonHelper.writeJson(session));
    }

    public void deleteCodeSession(String email) {
        deleteSetting(codeKey(email));
    }

    public NotifyEmailRateLimitSession findRateLimitSession(long userId) {
        String key = rateLimitKey(userId);
        NotifyEmailRateLimitSession session = jsonHelper.readObject(getSettingValue(key), NotifyEmailRateLimitSession.class);
        if (session == null) {
            return null;
        }
        if (session.expiresAt() == null || session.expiresAt().isBefore(Instant.now())) {
            deleteSetting(key);
            return null;
        }
        return session;
    }

    public void saveRateLimitSession(long userId, NotifyEmailRateLimitSession session) {
        upsertSetting(rateLimitKey(userId), jsonHelper.writeJson(session));
    }

    private String getSettingValue(String key) {
        List<String> values = jdbcTemplate.query("""
                select value
                from settings
                where key = :key
                limit 1
                """, new MapSqlParameterSource("key", key), (rs, rowNum) -> rs.getString("value"));
        return values.isEmpty() ? null : values.get(0);
    }

    private void upsertSetting(String key, String value) {
        jdbcTemplate.update("""
                insert into settings(key, value, created_at, updated_at)
                values (:key, :value, now(), now())
                on conflict (key)
                do update set value = excluded.value, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("value", value));
    }

    private void deleteSetting(String key) {
        jdbcTemplate.update("""
                delete from settings
                where key = :key
                """, new MapSqlParameterSource("key", key));
    }

    private String codeKey(String email) {
        return CODE_KEY_PREFIX + normalizeEmail(email);
    }

    private String rateLimitKey(long userId) {
        return RATE_LIMIT_KEY_PREFIX + userId;
    }

    private String normalizeEmail(String email) {
        return trimToEmpty(email).toLowerCase(Locale.ROOT);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record NotifyEmailUserRow(
            long id,
            List<NotifyEmailEntry> notifyEmails
    ) {
    }
}
