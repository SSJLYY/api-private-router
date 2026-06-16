package org.apiprivaterouter.javabackend.admin.compliance.repository;

import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AdminComplianceRepository {

    private static final Logger log = LoggerFactory.getLogger(AdminComplianceRepository.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AdminComplianceRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public Optional<Map<String, Object>> getAcknowledgement(long adminUserId) {
        String key = "admin_compliance_acknowledgement:" + adminUserId;
        String sql = """
                select value::text as value_json
                from settings
                where key = :key
                """;
        List<Map<String, Object>> rows = jdbcTemplate.query(sql,
                new MapSqlParameterSource("key", key),
                (rs, rowNum) -> jsonHelper.readObjectMap(rs.getString("value_json")));
        return rows.stream().findFirst();
    }

    public void saveAcknowledgement(long adminUserId, String version, String ipAddress, String userAgent) {
        String key = "admin_compliance_acknowledgement:" + adminUserId;
        Map<String, Object> value = Map.of(
                "version", version,
                "admin_user_id", adminUserId,
                "ip_address", ipAddress != null ? ipAddress : "",
                "user_agent", userAgent != null ? userAgent : "",
                "accepted_at", java.time.Instant.now().toString()
        );
        String valueJson = jsonHelper.writeJson(value);
        jdbcTemplate.update("""
                insert into settings (key, value, updated_at)
                values (:key, cast(:valueJson as jsonb), now())
                on conflict (key) do update set
                    value = cast(:valueJson as jsonb),
                    updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("valueJson", valueJson));
    }

    public Optional<String> getSettingValue(String key) {
        String sql = "select value::text as value_json from settings where key = :key";
        List<String> rows = jdbcTemplate.query(sql,
                new MapSqlParameterSource("key", key),
                (rs, rowNum) -> rs.getString("value_json"));
        return rows.stream().findFirst();
    }
}
