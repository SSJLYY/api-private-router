package org.apiprivaterouter.javabackend.userbindings.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.userbindings.model.EmailBindingCodeSession;
import org.apiprivaterouter.javabackend.userbindings.model.EmailBindingRateLimitSession;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class UserAccountBindingsRepository {

    private static final String CODE_KEY_PREFIX = "java:user-account-bindings:email-code:";
    private static final String RATE_LIMIT_KEY_PREFIX = "java:user-account-bindings:email-rate:";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public UserAccountBindingsRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public Optional<UserBindingUserRow> findActiveUserById(long userId) {
        List<UserBindingUserRow> rows = jdbcTemplate.query("""
                select id,
                       email,
                       password_hash,
                       role,
                       status,
                       signup_source,
                       token_version,
                       deleted_at
                from users
                where id = :userId
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> new UserBindingUserRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getString("signup_source"),
                rs.getObject("token_version", Long.class),
                rs.getTimestamp("deleted_at")
        ));
        return rows.stream()
                .filter(row -> row.deleted_at() == null && "active".equalsIgnoreCase(trimToEmpty(row.status())))
                .findFirst();
    }

    public Optional<UserBindingUserRow> findActiveUserByEmail(String email) {
        List<UserBindingUserRow> rows = jdbcTemplate.query("""
                select id,
                       email,
                       password_hash,
                       role,
                       status,
                       signup_source,
                       token_version,
                       deleted_at
                from users
                where lower(email) = :email
                """, new MapSqlParameterSource("email", normalizeEmail(email)), (rs, rowNum) -> new UserBindingUserRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getString("signup_source"),
                rs.getObject("token_version", Long.class),
                rs.getTimestamp("deleted_at")
        ));
        return rows.stream()
                .filter(row -> row.deleted_at() == null && "active".equalsIgnoreCase(trimToEmpty(row.status())))
                .findFirst();
    }

    public List<IdentityBindingRow> listIdentityBindings(long userId) {
        return jdbcTemplate.query("""
                select id,
                       user_id,
                       provider_type,
                       provider_key,
                       provider_subject,
                       issuer,
                       metadata::text as metadata_json,
                       verified_at,
                       updated_at
                from auth_identities
                where user_id = :userId
                order by id asc
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> new IdentityBindingRow(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("provider_type"),
                rs.getString("provider_key"),
                rs.getString("provider_subject"),
                rs.getString("issuer"),
                rs.getString("metadata_json"),
                rs.getObject("verified_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        ));
    }

    public void deleteProviderBindings(long userId, String provider) {
        jdbcTemplate.update("""
                delete from auth_identities
                where user_id = :userId
                  and provider_type = :provider
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("provider", provider));
    }

    public void updateUserEmailAndPassword(long userId, String email, String passwordHash) {
        jdbcTemplate.update("""
                update users
                set email = :email,
                    password_hash = :passwordHash,
                    updated_at = now()
                where id = :userId
                  and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("email", email)
                .addValue("passwordHash", passwordHash));
    }

    public void ensureEmailIdentity(long userId, String email, String source) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("providerType", "email")
                .addValue("providerKey", "email")
                .addValue("providerSubject", email)
                .addValue("metadataJson", buildMetadataJson(source));
        jdbcTemplate.update("""
                insert into auth_identities (
                    user_id,
                    provider_type,
                    provider_key,
                    provider_subject,
                    verified_at,
                    metadata,
                    created_at,
                    updated_at
                )
                values (
                    :userId,
                    :providerType,
                    :providerKey,
                    :providerSubject,
                    now(),
                    cast(:metadataJson as jsonb),
                    now(),
                    now()
                )
                on conflict (provider_type, provider_key, provider_subject)
                do nothing
                """, params);

        Long ownerUserId = findEmailIdentityOwner(email);
        if (ownerUserId != null && ownerUserId != userId) {
            throw new DuplicateKeyException("email identity already exists");
        }

        jdbcTemplate.update("""
                update auth_identities
                set verified_at = now(),
                    metadata = cast(:metadataJson as jsonb),
                    updated_at = now()
                where user_id = :userId
                  and provider_type = :providerType
                  and provider_key = :providerKey
                  and provider_subject = :providerSubject
                """, params);
    }

    public void deleteEmailIdentityBySubject(long userId, String email) {
        jdbcTemplate.update("""
                delete from auth_identities
                where user_id = :userId
                  and provider_type = 'email'
                  and provider_key = 'email'
                  and provider_subject = :email
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("email", email));
    }

    public void bumpUserTokenVersion(long userId) {
        jdbcTemplate.update("""
                update users
                set token_version = coalesce(token_version, 0) + 1,
                    updated_at = now()
                where id = :userId
                  and deleted_at is null
                """, new MapSqlParameterSource("userId", userId));
    }

    public Long findEmailIdentityOwner(String email) {
        List<Long> owners = jdbcTemplate.query("""
                select user_id
                from auth_identities
                where provider_type = 'email'
                  and provider_key = 'email'
                  and provider_subject = :email
                limit 1
                """, new MapSqlParameterSource("email", normalizeEmail(email)), (rs, rowNum) -> rs.getLong("user_id"));
        return owners.isEmpty() ? null : owners.get(0);
    }

    public EmailBindingCodeSession findCodeSession(String email) {
        String key = codeKey(email);
        EmailBindingCodeSession session = jsonHelper.readObject(getSettingValue(key), EmailBindingCodeSession.class);
        if (session == null) {
            return null;
        }
        if (session.expiresAt() == null || session.expiresAt().isBefore(Instant.now())) {
            deleteSetting(key);
            return null;
        }
        return session;
    }

    public void saveCodeSession(String email, EmailBindingCodeSession session) {
        upsertSetting(codeKey(email), jsonHelper.writeJson(session));
    }

    public void deleteCodeSession(String email) {
        deleteSetting(codeKey(email));
    }

    public EmailBindingRateLimitSession findRateLimitSession(long userId) {
        String key = rateLimitKey(userId);
        EmailBindingRateLimitSession session = jsonHelper.readObject(getSettingValue(key), EmailBindingRateLimitSession.class);
        if (session == null) {
            return null;
        }
        if (session.expiresAt() == null || session.expiresAt().isBefore(Instant.now())) {
            deleteSetting(key);
            return null;
        }
        return session;
    }

    public void saveRateLimitSession(long userId, EmailBindingRateLimitSession session) {
        upsertSetting(rateLimitKey(userId), jsonHelper.writeJson(session));
    }

    public Map<String, String> getSettingValues(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select key, value
                from settings
                where key in (:keys)
                """, new MapSqlParameterSource("keys", keys), (rs, rowNum) -> {
            values.put(rs.getString("key"), rs.getString("value"));
            return null;
        });
        return values;
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

    private String buildMetadataJson(String source) {
        String normalized = trimToEmpty(source);
        if (normalized.isEmpty()) {
            normalized = "auth_service_email_bind";
        }
        return "{\"source\":\"" + normalized.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private String normalizeEmail(String email) {
        return trimToEmpty(email).toLowerCase(Locale.ROOT);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record UserBindingUserRow(
            long id,
            String email,
            String password_hash,
            String role,
            String status,
            String signup_source,
            Long token_version,
            Timestamp deleted_at
    ) {
    }

    public record IdentityBindingRow(
            long id,
            long user_id,
            String provider_type,
            String provider_key,
            String provider_subject,
            String issuer,
            String metadata_json,
            OffsetDateTime verified_at,
            OffsetDateTime updated_at
    ) {
    }
}
