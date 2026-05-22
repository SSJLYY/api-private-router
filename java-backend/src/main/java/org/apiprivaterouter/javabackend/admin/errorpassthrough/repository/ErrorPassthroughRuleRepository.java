package org.apiprivaterouter.javabackend.admin.errorpassthrough.repository;

import org.apiprivaterouter.javabackend.admin.errorpassthrough.model.ErrorPassthroughRuleRecord;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ErrorPassthroughRuleRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public ErrorPassthroughRuleRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public List<ErrorPassthroughRuleRecord> list() {
        return jdbcTemplate.query("""
                select id, name, enabled, priority, error_codes, keywords, match_mode, platforms,
                       passthrough_code, response_code, passthrough_body, custom_message,
                       skip_monitoring, description, created_at, updated_at
                from error_passthrough_rules
                order by priority asc, id asc
                """, new MapSqlParameterSource(), (rs, rowNum) -> mapRecord(rs));
    }

    public Optional<ErrorPassthroughRuleRecord> getById(long id) {
        List<ErrorPassthroughRuleRecord> rows = jdbcTemplate.query("""
                select id, name, enabled, priority, error_codes, keywords, match_mode, platforms,
                       passthrough_code, response_code, passthrough_body, custom_message,
                       skip_monitoring, description, created_at, updated_at
                from error_passthrough_rules
                where id = :id
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapRecord(rs));
        return rows.stream().findFirst();
    }

    public ErrorPassthroughRuleRecord create(ErrorPassthroughRuleRecord rule) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into error_passthrough_rules (
                    name, enabled, priority, error_codes, keywords, match_mode, platforms,
                    passthrough_code, response_code, passthrough_body, custom_message,
                    skip_monitoring, description, created_at, updated_at
                ) values (
                    :name, :enabled, :priority, cast(:errorCodes as jsonb), cast(:keywords as jsonb), :matchMode, cast(:platforms as jsonb),
                    :passthroughCode, :responseCode, :passthroughBody, :customMessage,
                    :skipMonitoring, :description, now(), now()
                )
                returning id
                """, params(rule), keyHolder, new String[]{"id"});
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("failed to create error passthrough rule");
        }
        return getById(id.longValue()).orElseThrow(() -> new IllegalStateException("created rule not found"));
    }

    public ErrorPassthroughRuleRecord update(ErrorPassthroughRuleRecord rule) {
        int updated = jdbcTemplate.update("""
                update error_passthrough_rules
                set name = :name,
                    enabled = :enabled,
                    priority = :priority,
                    error_codes = cast(:errorCodes as jsonb),
                    keywords = cast(:keywords as jsonb),
                    match_mode = :matchMode,
                    platforms = cast(:platforms as jsonb),
                    passthrough_code = :passthroughCode,
                    response_code = :responseCode,
                    passthrough_body = :passthroughBody,
                    custom_message = :customMessage,
                    skip_monitoring = :skipMonitoring,
                    description = :description,
                    updated_at = now()
                where id = :id
                """, params(rule).addValue("id", rule.id()));
        if (updated <= 0) {
            throw new IllegalArgumentException("rule not found");
        }
        return getById(rule.id()).orElseThrow(() -> new IllegalArgumentException("rule not found"));
    }

    public boolean delete(long id) {
        return jdbcTemplate.update("""
                delete from error_passthrough_rules
                where id = :id
                """, new MapSqlParameterSource("id", id)) > 0;
    }

    private MapSqlParameterSource params(ErrorPassthroughRuleRecord rule) {
        return new MapSqlParameterSource()
                .addValue("name", rule.name())
                .addValue("enabled", rule.enabled())
                .addValue("priority", rule.priority())
                .addValue("errorCodes", jsonHelper.writeJson(rule.errorCodes()))
                .addValue("keywords", jsonHelper.writeJson(rule.keywords()))
                .addValue("matchMode", rule.matchMode())
                .addValue("platforms", jsonHelper.writeJson(rule.platforms()))
                .addValue("passthroughCode", rule.passthroughCode())
                .addValue("responseCode", rule.responseCode())
                .addValue("passthroughBody", rule.passthroughBody())
                .addValue("customMessage", rule.customMessage())
                .addValue("skipMonitoring", rule.skipMonitoring())
                .addValue("description", rule.description());
    }

    private ErrorPassthroughRuleRecord mapRecord(ResultSet rs) throws SQLException {
        return new ErrorPassthroughRuleRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getBoolean("enabled"),
                rs.getInt("priority"),
                readIntList(rs.getString("error_codes")),
                readStringList(rs.getString("keywords")),
                rs.getString("match_mode"),
                readStringList(rs.getString("platforms")),
                rs.getBoolean("passthrough_code"),
                rs.getObject("response_code", Integer.class),
                rs.getBoolean("passthrough_body"),
                rs.getString("custom_message"),
                rs.getBoolean("skip_monitoring"),
                rs.getString("description"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private List<Integer> readIntList(String raw) {
        List<Integer> list = jsonHelper.readList(raw, Integer.class);
        return list == null ? List.of() : list;
    }

    private List<String> readStringList(String raw) {
        List<String> list = jsonHelper.readList(raw, String.class);
        return list == null ? List.of() : list;
    }
}
