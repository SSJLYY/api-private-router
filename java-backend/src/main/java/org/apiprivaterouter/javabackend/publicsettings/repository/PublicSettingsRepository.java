package org.apiprivaterouter.javabackend.publicsettings.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class PublicSettingsRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PublicSettingsRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, String> getValues(List<String> keys) {
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
}
