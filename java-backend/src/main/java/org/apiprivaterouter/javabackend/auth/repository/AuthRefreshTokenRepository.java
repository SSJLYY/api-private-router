package org.apiprivaterouter.javabackend.auth.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Repository
public class AuthRefreshTokenRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AtomicBoolean schemaInitialized = new AtomicBoolean(false);

    public AuthRefreshTokenRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void store(RefreshTokenRow tokenRow) {
        ensureSchema();
        jdbcTemplate.update("""
                insert into java_auth_refresh_tokens (
                    token_hash,
                    user_id,
                    token_version,
                    family_id,
                    created_at,
                    expires_at
                )
                values (
                    :tokenHash,
                    :userId,
                    :tokenVersion,
                    :familyId,
                    :createdAt,
                    :expiresAt
                )
                on conflict (token_hash)
                do update set user_id = excluded.user_id,
                              token_version = excluded.token_version,
                              family_id = excluded.family_id,
                              created_at = excluded.created_at,
                              expires_at = excluded.expires_at
                """, new MapSqlParameterSource()
                .addValue("tokenHash", tokenRow.tokenHash())
                .addValue("userId", tokenRow.userId())
                .addValue("tokenVersion", tokenRow.tokenVersion())
                .addValue("familyId", tokenRow.familyId())
                .addValue("createdAt", OffsetDateTime.ofInstant(tokenRow.createdAt(), OffsetDateTime.now().getOffset()))
                .addValue("expiresAt", OffsetDateTime.ofInstant(tokenRow.expiresAt(), OffsetDateTime.now().getOffset())));
    }

    public Optional<RefreshTokenRow> find(String tokenHash) {
        ensureSchema();
        List<RefreshTokenRow> rows = jdbcTemplate.query("""
                select token_hash,
                       user_id,
                       token_version,
                       family_id,
                       created_at,
                       expires_at
                from java_auth_refresh_tokens
                where token_hash = :tokenHash
                limit 1
                """, new MapSqlParameterSource("tokenHash", tokenHash), (rs, rowNum) -> mapRow(
                rs.getString("token_hash"),
                rs.getLong("user_id"),
                rs.getLong("token_version"),
                rs.getString("family_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class)
        ));
        return rows.stream().findFirst();
    }

    public Optional<RefreshTokenRow> consume(String tokenHash) {
        ensureSchema();
        List<RefreshTokenRow> rows = jdbcTemplate.query("""
                delete from java_auth_refresh_tokens
                where token_hash = :tokenHash
                returning token_hash,
                          user_id,
                          token_version,
                          family_id,
                          created_at,
                          expires_at
                """, new MapSqlParameterSource("tokenHash", tokenHash), (rs, rowNum) -> mapRow(
                rs.getString("token_hash"),
                rs.getLong("user_id"),
                rs.getLong("token_version"),
                rs.getString("family_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class)
        ));
        return rows.stream().findFirst();
    }

    public int delete(String tokenHash) {
        ensureSchema();
        return jdbcTemplate.update("""
                delete from java_auth_refresh_tokens
                where token_hash = :tokenHash
                """, new MapSqlParameterSource("tokenHash", tokenHash));
    }

    public int deleteByUserId(long userId) {
        ensureSchema();
        return jdbcTemplate.update("""
                delete from java_auth_refresh_tokens
                where user_id = :userId
                """, new MapSqlParameterSource("userId", userId));
    }

    private RefreshTokenRow mapRow(
            String tokenHash,
            long userId,
            long tokenVersion,
            String familyId,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt
    ) {
        return new RefreshTokenRow(
                tokenHash,
                userId,
                tokenVersion,
                familyId,
                createdAt == null ? Instant.EPOCH : createdAt.toInstant(),
                expiresAt == null ? Instant.EPOCH : expiresAt.toInstant()
        );
    }

    private void initSchema() {
        jdbcTemplate.getJdbcTemplate().execute("""
                create table if not exists java_auth_refresh_tokens (
                    token_hash varchar(128) primary key,
                    user_id bigint not null,
                    token_version bigint not null,
                    family_id varchar(128) not null,
                    created_at timestamptz not null,
                    expires_at timestamptz not null
                )
                """);
        jdbcTemplate.getJdbcTemplate().execute("""
                create index if not exists idx_java_auth_refresh_tokens_user_id
                on java_auth_refresh_tokens(user_id)
                """);
        jdbcTemplate.getJdbcTemplate().execute("""
                create index if not exists idx_java_auth_refresh_tokens_family_id
                on java_auth_refresh_tokens(family_id)
                """);
    }

    private void ensureSchema() {
        if (schemaInitialized.compareAndSet(false, true)) {
            initSchema();
        }
    }
    public record RefreshTokenRow(
            String tokenHash,
            long userId,
            long tokenVersion,
            String familyId,
            Instant createdAt,
            Instant expiresAt
    ) {
    }
}
