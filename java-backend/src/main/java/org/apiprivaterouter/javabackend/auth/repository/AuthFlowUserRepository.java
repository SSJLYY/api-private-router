package org.apiprivaterouter.javabackend.auth.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class AuthFlowUserRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AuthFlowUserRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AuthUserRow> findByEmail(String email) {
        List<AuthUserRow> rows = jdbcTemplate.query("""
                select id,
                       email,
                       username,
                       password_hash,
                       role,
                       status,
                       coalesce(token_version, 0) as token_version,
                       coalesce(totp_enabled, false) as totp_enabled
                from users
                where lower(email) = :email
                  and deleted_at is null
                limit 1
                """, new MapSqlParameterSource("email", normalizeEmail(email)), (rs, rowNum) -> new AuthUserRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getLong("token_version"),
                rs.getBoolean("totp_enabled")
        ));
        return rows.stream().findFirst();
    }

    public Optional<AuthUserRow> findById(long userId) {
        List<AuthUserRow> rows = jdbcTemplate.query("""
                select id,
                       email,
                       username,
                       password_hash,
                       role,
                       status,
                       coalesce(token_version, 0) as token_version,
                       coalesce(totp_enabled, false) as totp_enabled
                from users
                where id = :userId
                  and deleted_at is null
                limit 1
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> new AuthUserRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getLong("token_version"),
                rs.getBoolean("totp_enabled")
        ));
        return rows.stream().findFirst();
    }

    public void touchSuccessfulLogin(long userId) {
        jdbcTemplate.update("""
                update users
                set last_login_at = now(),
                    last_active_at = now(),
                    updated_at = now()
                where id = :userId
                  and deleted_at is null
                """, new MapSqlParameterSource("userId", userId));
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public record AuthUserRow(
            long id,
            String email,
            String username,
            String password_hash,
            String role,
            String status,
            long token_version,
            boolean totp_enabled
    ) {
    }
}
