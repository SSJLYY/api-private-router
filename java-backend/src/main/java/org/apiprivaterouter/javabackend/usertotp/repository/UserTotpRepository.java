package org.apiprivaterouter.javabackend.usertotp.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class UserTotpRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public UserTotpRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TotpUserRow> findUserById(long userId) {
        List<TotpUserRow> rows = jdbcTemplate.query("""
                select id,
                       email,
                       password_hash,
                       totp_secret_encrypted,
                       totp_enabled,
                       totp_enabled_at
                from users
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> new TotpUserRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("totp_secret_encrypted"),
                rs.getBoolean("totp_enabled"),
                rs.getObject("totp_enabled_at", OffsetDateTime.class)
        ));
        return rows.stream().findFirst();
    }

    public void updateTotpSecret(long userId, String encryptedSecret) {
        jdbcTemplate.update("""
                update users
                set totp_secret_encrypted = :encryptedSecret,
                    updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("encryptedSecret", encryptedSecret));
    }

    public void enableTotp(long userId) {
        jdbcTemplate.update("""
                update users
                set totp_enabled = true,
                    totp_enabled_at = now(),
                    updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource("userId", userId));
    }

    public void disableTotp(long userId) {
        jdbcTemplate.update("""
                update users
                set totp_enabled = false,
                    totp_secret_encrypted = null,
                    totp_enabled_at = null,
                    updated_at = now()
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource("userId", userId));
    }

    public Map<String, String> getSettings(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select key, value
                from settings
                where key in (:keys)
                """, new MapSqlParameterSource("keys", keys), rs -> {
            values.put(rs.getString("key"), rs.getString("value"));
        });
        return values;
    }

    public record TotpUserRow(
            long id,
            String email,
            String password_hash,
            String totp_secret_encrypted,
            boolean totp_enabled,
            OffsetDateTime totp_enabled_at
    ) {
    }
}
