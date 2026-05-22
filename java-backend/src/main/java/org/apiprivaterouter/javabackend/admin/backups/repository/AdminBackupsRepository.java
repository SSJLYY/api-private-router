package org.apiprivaterouter.javabackend.admin.backups.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AdminBackupsRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminBackupsRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String getSetting(String key) {
        List<String> values = jdbcTemplate.query("""
                select value
                from settings
                where key = :key
                limit 1
                """, new MapSqlParameterSource("key", key), (rs, rowNum) -> rs.getString("value"));
        return values.isEmpty() ? null : values.get(0);
    }

    public void upsertSetting(String key, String value) {
        jdbcTemplate.update("""
                insert into settings (key, value, created_at, updated_at)
                values (:key, :value, now(), now())
                on conflict (key)
                do update set value = excluded.value, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("value", value));
    }

    public AdminPasswordRow findAdminPasswordByUserId(long userId) {
        List<AdminPasswordRow> rows = jdbcTemplate.query("""
                select id, password_hash, role
                from users
                where id = :id
                  and deleted_at is null
                limit 1
                """, new MapSqlParameterSource("id", userId), (rs, rowNum) -> new AdminPasswordRow(
                rs.getLong("id"),
                rs.getString("password_hash"),
                rs.getString("role")
        ));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public record AdminPasswordRow(
            long id,
            String password_hash,
            String role
    ) {
    }
}
