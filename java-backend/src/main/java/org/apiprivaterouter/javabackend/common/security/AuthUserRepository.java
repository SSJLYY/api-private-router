package org.apiprivaterouter.javabackend.common.security;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AuthUserRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AuthUserRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CurrentUser> findActiveUserById(long userId) {
        String sql = """
                select id, email, role, status, password_hash, coalesce(token_version, 0) as token_version
                from users
                where id = :userId and deleted_at is null
                """;
        List<CurrentUser> rows = jdbcTemplate.query(sql, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> {
            String status = rs.getString("status");
            if (!"active".equalsIgnoreCase(status)) {
                return null;
            }
            String email = rs.getString("email");
            String passwordHash = rs.getString("password_hash");
            long rawTokenVersion = rs.getLong("token_version");
            return new CurrentUser(
                    rs.getLong("id"),
                    email,
                    rs.getString("role"),
                    TokenVersionResolver.resolve(email, passwordHash, rawTokenVersion)
            );
        });
        return rows.stream().filter(user -> user != null).findFirst();
    }
}
