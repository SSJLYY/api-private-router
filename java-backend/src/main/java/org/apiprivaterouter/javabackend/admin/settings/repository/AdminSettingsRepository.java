package org.apiprivaterouter.javabackend.admin.settings.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class AdminSettingsRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminSettingsRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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

    public Map<String, String> getSettingValues(List<String> keys) {
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

    public void upsertSettingValue(String key, String value) {
        jdbcTemplate.update("""
                insert into settings(key, value, created_at, updated_at)
                values (:key, :value, now(), now())
                on conflict (key)
                do update set value = excluded.value, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("value", value));
    }

    public void upsertSettingValues(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            upsertSettingValue(entry.getKey(), entry.getValue());
        }
    }

    public void deleteSetting(String key) {
        jdbcTemplate.update("""
                delete from settings
                where key = :key
                """, new MapSqlParameterSource("key", key));
    }
}
