package org.apiprivaterouter.javabackend.auth.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class AuthPublicEmailRepository {

    private static final String VERIFY_CODE_KEY_PREFIX = "java:auth:verify-code:";
    private static final String PASSWORD_RESET_TOKEN_KEY_PREFIX = "java:auth:password-reset:";
    private static final String PASSWORD_RESET_EMAIL_COOLDOWN_KEY_PREFIX = "java:auth:password-reset-cooldown:";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AuthPublicEmailRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public Optional<PublicAuthUserRow> findUserByEmail(String email) {
        List<PublicAuthUserRow> rows = jdbcTemplate.query("""
                select id,
                       email,
                       username,
                       password_hash,
                       role,
                       status,
                       coalesce(balance, 0) as balance,
                       coalesce(concurrency, 1) as concurrency,
                       rpm_limit,
                       coalesce(token_version, 0) as token_version,
                       signup_source,
                       deleted_at
                from users
                where lower(email) = :email
                limit 1
                """, new MapSqlParameterSource("email", normalizeEmail(email)), (rs, rowNum) -> new PublicAuthUserRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getDouble("balance"),
                rs.getInt("concurrency"),
                rs.getObject("rpm_limit", Integer.class),
                rs.getLong("token_version"),
                rs.getString("signup_source"),
                rs.getTimestamp("deleted_at")
        ));
        return rows.stream().filter(row -> row.deleted_at() == null).findFirst();
    }

    public Optional<PublicAuthUserRow> findUserById(long userId) {
        List<PublicAuthUserRow> rows = jdbcTemplate.query("""
                select id,
                       email,
                       username,
                       password_hash,
                       role,
                       status,
                       coalesce(balance, 0) as balance,
                       coalesce(concurrency, 1) as concurrency,
                       rpm_limit,
                       coalesce(token_version, 0) as token_version,
                       signup_source,
                       deleted_at
                from users
                where id = :userId
                limit 1
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> new PublicAuthUserRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getDouble("balance"),
                rs.getInt("concurrency"),
                rs.getObject("rpm_limit", Integer.class),
                rs.getLong("token_version"),
                rs.getString("signup_source"),
                rs.getTimestamp("deleted_at")
        ));
        return rows.stream().filter(row -> row.deleted_at() == null).findFirst();
    }

    public boolean existsActiveUserByEmail(String email) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                    from users
                    where lower(email) = :email
                      and deleted_at is null
                )
                """, new MapSqlParameterSource("email", normalizeEmail(email)), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<RedeemCodeRow> findRedeemCodeByCodeForUpdate(String code) {
        List<RedeemCodeRow> rows = jdbcTemplate.query("""
                select id, code, type, status, used_by, group_id, coalesce(validity_days, 0) as validity_days
                from redeem_codes
                where code = :code
                for update
                """, new MapSqlParameterSource("code", trimToEmpty(code)), (rs, rowNum) -> new RedeemCodeRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("type"),
                rs.getString("status"),
                rs.getObject("used_by", Long.class),
                rs.getObject("group_id", Long.class),
                rs.getInt("validity_days")
        ));
        return rows.stream().findFirst();
    }

    public void markRedeemCodeUsed(long redeemCodeId, long userId) {
        jdbcTemplate.update("""
                update redeem_codes
                set status = 'used',
                    used_by = :userId,
                    used_at = now()
                where id = :redeemCodeId
                  and status = 'unused'
                """, new MapSqlParameterSource()
                .addValue("redeemCodeId", redeemCodeId)
                .addValue("userId", userId));
    }

    public Optional<PromoCodeRow> findPromoCodeByCode(String code) {
        List<PromoCodeRow> rows = jdbcTemplate.query("""
                select id,
                       code,
                       bonus_amount,
                       coalesce(max_uses, 0) as max_uses,
                       coalesce(used_count, 0) as used_count,
                       status,
                       expires_at
                from promo_codes
                where upper(code) = upper(:code)
                limit 1
                """, new MapSqlParameterSource("code", trimToEmpty(code)), (rs, rowNum) -> new PromoCodeRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getDouble("bonus_amount"),
                rs.getInt("max_uses"),
                rs.getInt("used_count"),
                rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class)
        ));
        return rows.stream().findFirst();
    }

    public Optional<PromoCodeRow> findPromoCodeByCodeForUpdate(String code) {
        List<PromoCodeRow> rows = jdbcTemplate.query("""
                select id,
                       code,
                       bonus_amount,
                       coalesce(max_uses, 0) as max_uses,
                       coalesce(used_count, 0) as used_count,
                       status,
                       expires_at
                from promo_codes
                where upper(code) = upper(:code)
                for update
                """, new MapSqlParameterSource("code", trimToEmpty(code)), (rs, rowNum) -> new PromoCodeRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getDouble("bonus_amount"),
                rs.getInt("max_uses"),
                rs.getInt("used_count"),
                rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class)
        ));
        return rows.stream().findFirst();
    }

    public boolean hasPromoCodeUsage(long promoCodeId, long userId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                    from promo_code_usages
                    where promo_code_id = :promoCodeId
                      and user_id = :userId
                )
                """, new MapSqlParameterSource()
                .addValue("promoCodeId", promoCodeId)
                .addValue("userId", userId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public void applyPromoCode(long promoCodeId, long userId, double bonusAmount) {
        jdbcTemplate.update("""
                update users
                set balance = coalesce(balance, 0) + :bonusAmount,
                    updated_at = now()
                where id = :userId
                  and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("bonusAmount", bonusAmount)
                .addValue("userId", userId));

        jdbcTemplate.update("""
                insert into promo_code_usages (
                    promo_code_id,
                    user_id,
                    bonus_amount,
                    used_at
                )
                values (
                    :promoCodeId,
                    :userId,
                    :bonusAmount,
                    now()
                )
                """, new MapSqlParameterSource()
                .addValue("promoCodeId", promoCodeId)
                .addValue("userId", userId)
                .addValue("bonusAmount", bonusAmount));

        jdbcTemplate.update("""
                update promo_codes
                set used_count = coalesce(used_count, 0) + 1,
                    updated_at = now()
                where id = :promoCodeId
                """, new MapSqlParameterSource("promoCodeId", promoCodeId));
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
                """, new MapSqlParameterSource("keys", keys), (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                values.put(rs.getString("key"), rs.getString("value")));
        return values;
    }

    public String getSettingValue(String key) {
        List<String> values = jdbcTemplate.query("""
                select value
                from settings
                where key = :key
                limit 1
                """, new MapSqlParameterSource("key", key), (rs, rowNum) -> rs.getString("value"));
        return values.isEmpty() ? null : values.get(0);
    }

    public VerifyCodeSession findVerifyCodeSession(String email) {
        VerifyCodeSession session = jsonHelper.readObject(getSettingValue(verifyCodeKey(email)), VerifyCodeSession.class);
        if (session == null) {
            return null;
        }
        if (session.expiresAt() == null || session.expiresAt().isBefore(Instant.now())) {
            deleteSetting(verifyCodeKey(email));
            return null;
        }
        return session;
    }

    public void saveVerifyCodeSession(String email, VerifyCodeSession session) {
        upsertSetting(verifyCodeKey(email), jsonHelper.writeJson(session));
    }

    public void deleteVerifyCodeSession(String email) {
        deleteSetting(verifyCodeKey(email));
    }

    public PasswordResetTokenSession findPasswordResetTokenSession(String email) {
        PasswordResetTokenSession session = jsonHelper.readObject(getSettingValue(passwordResetTokenKey(email)), PasswordResetTokenSession.class);
        if (session == null) {
            return null;
        }
        if (session.expiresAt() == null || session.expiresAt().isBefore(Instant.now())) {
            deleteSetting(passwordResetTokenKey(email));
            return null;
        }
        return session;
    }

    public void savePasswordResetTokenSession(String email, PasswordResetTokenSession session) {
        upsertSetting(passwordResetTokenKey(email), jsonHelper.writeJson(session));
    }

    public void deletePasswordResetTokenSession(String email) {
        deleteSetting(passwordResetTokenKey(email));
    }

    public PasswordResetEmailCooldownSession findPasswordResetEmailCooldownSession(String email) {
        PasswordResetEmailCooldownSession session = jsonHelper.readObject(
                getSettingValue(passwordResetEmailCooldownKey(email)),
                PasswordResetEmailCooldownSession.class
        );
        if (session == null) {
            return null;
        }
        if (session.expiresAt() == null || session.expiresAt().isBefore(Instant.now())) {
            deleteSetting(passwordResetEmailCooldownKey(email));
            return null;
        }
        return session;
    }

    public void savePasswordResetEmailCooldownSession(String email, PasswordResetEmailCooldownSession session) {
        upsertSetting(passwordResetEmailCooldownKey(email), jsonHelper.writeJson(session));
    }

    public long createUser(CreateUserCommand command) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into users (
                    email,
                    password_hash,
                    role,
                    balance,
                    concurrency,
                    status,
                    username,
                    notes,
                    totp_enabled,
                    signup_source,
                    balance_notify_enabled,
                    balance_notify_threshold_type,
                    balance_notify_extra_emails,
                    total_recharged,
                    rpm_limit,
                    created_at,
                    updated_at,
                    last_login_at,
                    last_active_at
                ) values (
                    :email,
                    :passwordHash,
                    :role,
                    :balance,
                    :concurrency,
                    :status,
                    :username,
                    :notes,
                    false,
                    :signupSource,
                    true,
                    'fixed',
                    '[]',
                    0,
                    :rpmLimit,
                    now(),
                    now(),
                    now(),
                    now()
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("email", normalizeEmail(command.email()))
                .addValue("passwordHash", command.passwordHash())
                .addValue("role", trimToEmpty(command.role()))
                .addValue("balance", command.balance())
                .addValue("concurrency", command.concurrency())
                .addValue("status", trimToEmpty(command.status()))
                .addValue("username", trimToEmpty(command.username()))
                .addValue("notes", trimToEmpty(command.notes()))
                .addValue("signupSource", trimToEmpty(command.signupSource()))
                .addValue("rpmLimit", command.rpmLimit()), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to create user");
        }
        return key.longValue();
    }

    public void ensureEmailIdentity(long userId, String email, String source) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("providerType", "email")
                .addValue("providerKey", "email")
                .addValue("providerSubject", normalizeEmail(email))
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

        Long owner = findEmailIdentityOwner(email);
        if (owner != null && owner != userId) {
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

    public Long findEmailIdentityOwner(String email) {
        List<Long> rows = jdbcTemplate.query("""
                select user_id
                from auth_identities
                where provider_type = 'email'
                  and provider_key = 'email'
                  and provider_subject = :email
                limit 1
                """, new MapSqlParameterSource("email", normalizeEmail(email)), (rs, rowNum) -> rs.getLong("user_id"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void incrementUserTokenVersionAndPassword(long userId, String passwordHash) {
        jdbcTemplate.update("""
                update users
                set password_hash = :passwordHash,
                    token_version = coalesce(token_version, 0) + 1,
                    updated_at = now()
                where id = :userId
                  and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("passwordHash", passwordHash));
    }

    public void incrementUserTokenVersion(long userId) {
        jdbcTemplate.update("""
                update users
                set token_version = coalesce(token_version, 0) + 1,
                    updated_at = now()
                where id = :userId
                  and deleted_at is null
                """, new MapSqlParameterSource("userId", userId));
    }

    public void ensureUserAffiliate(long userId) {
        jdbcTemplate.update("""
                insert into user_affiliates (
                    user_id,
                    aff_code,
                    aff_quota,
                    aff_history_quota,
                    aff_count,
                    created_at,
                    updated_at
                )
                values (
                    :userId,
                    :affCode,
                    0,
                    0,
                    0,
                    now(),
                    now()
                )
                on conflict (user_id)
                do nothing
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("affCode", "AFF-" + userId));
    }

    public Optional<Long> findAffiliateUserIdByCodeForUpdate(String code) {
        List<Long> rows = jdbcTemplate.query("""
                select user_id
                from user_affiliates
                where upper(aff_code) = :code
                limit 1
                for update
                """, new MapSqlParameterSource("code", normalizeAffiliateCode(code)), (rs, rowNum) -> rs.getLong("user_id"));
        return rows.stream().findFirst();
    }

    public Optional<Long> findAffiliateInviterIdForUpdate(long userId) {
        List<Long> rows = jdbcTemplate.query("""
                select inviter_id
                from user_affiliates
                where user_id = :userId
                for update
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> rs.getObject("inviter_id", Long.class));
        return rows.stream().filter(value -> value != null && value > 0).findFirst();
    }

    public boolean bindAffiliateInviter(long userId, long inviterId) {
        int updated = jdbcTemplate.update("""
                update user_affiliates
                set inviter_id = :inviterId,
                    updated_at = now()
                where user_id = :userId
                  and inviter_id is null
                  and user_id <> :inviterId
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("inviterId", inviterId));
        if (updated == 0) {
            return false;
        }
        jdbcTemplate.update("""
                update user_affiliates
                set aff_count = aff_count + 1,
                    updated_at = now()
                where user_id = :inviterId
                """, new MapSqlParameterSource("inviterId", inviterId));
        return true;
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

    private String verifyCodeKey(String email) {
        return VERIFY_CODE_KEY_PREFIX + normalizeEmail(email);
    }

    private String passwordResetTokenKey(String email) {
        return PASSWORD_RESET_TOKEN_KEY_PREFIX + normalizeEmail(email);
    }

    private String passwordResetEmailCooldownKey(String email) {
        return PASSWORD_RESET_EMAIL_COOLDOWN_KEY_PREFIX + normalizeEmail(email);
    }

    private String buildMetadataJson(String source) {
        String normalized = trimToEmpty(source);
        if (normalized.isEmpty()) {
            normalized = "auth_service_dual_write";
        }
        return "{\"source\":\"" + normalized.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private String normalizeEmail(String email) {
        return trimToEmpty(email).toLowerCase(Locale.ROOT);
    }

    private String normalizeAffiliateCode(String code) {
        return trimToEmpty(code).toUpperCase(Locale.ROOT);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record PublicAuthUserRow(
            long id,
            String email,
            String username,
            String password_hash,
            String role,
            String status,
            double balance,
            int concurrency,
            Integer rpm_limit,
            long token_version,
            String signup_source,
            Timestamp deleted_at
    ) {
    }

    public record RedeemCodeRow(
            long id,
            String code,
            String type,
            String status,
            Long usedBy,
            Long groupId,
            int validityDays
    ) {
    }

    public record PromoCodeRow(
            long id,
            String code,
            double bonusAmount,
            int maxUses,
            int usedCount,
            String status,
            OffsetDateTime expiresAt
    ) {
    }

    public record VerifyCodeSession(
            String code,
            int attempts,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    public record PasswordResetTokenSession(
            String token,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    public record PasswordResetEmailCooldownSession(
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    public record CreateUserCommand(
            String email,
            String passwordHash,
            String role,
            double balance,
            int concurrency,
            Integer rpmLimit,
            String status,
            String username,
            String notes,
            String signupSource
    ) {
    }
}
