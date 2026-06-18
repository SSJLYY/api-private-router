package org.apiprivaterouter.javabackend.settings.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmailUnsubscribeRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public EmailUnsubscribeRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isUnsubscribed(String preferenceKey) {
        String sql = """
                select value from system_settings
                where key = :key and deleted_at is null
                """;
        var rows = jdbcTemplate.query(sql, new MapSqlParameterSource("key", preferenceKey),
                (rs, rowNum) -> rs.getString("value"));
        return rows.stream().findFirst()
                .map("unsubscribed"::equalsIgnoreCase)
                .orElse(false);
    }

    public void setPreference(String preferenceKey, String value) {
        jdbcTemplate.update("""
                insert into system_settings (key, value, created_at, updated_at)
                values (:key, :value, now(), now())
                on conflict (key)
                do update set value = excluded.value, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("key", preferenceKey)
                .addValue("value", value));
    }

    public String getSecret(String key) {
        String sql = """
                select value from system_settings
                where key = :key and deleted_at is null
                """;
        var rows = jdbcTemplate.query(sql, new MapSqlParameterSource("key", key),
                (rs, rowNum) -> rs.getString("value"));
        return rows.stream().findFirst().orElse(null);
    }

    public void setSecret(String key, String value) {
        jdbcTemplate.update("""
                insert into system_settings (key, value, created_at, updated_at)
                values (:key, :value, now(), now())
                on conflict (key)
                do update set value = excluded.value, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("value", value));
    }
}
