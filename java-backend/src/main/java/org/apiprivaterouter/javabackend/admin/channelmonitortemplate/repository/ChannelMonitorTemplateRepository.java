package org.apiprivaterouter.javabackend.admin.channelmonitortemplate.repository;

import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.AssociatedMonitorBriefResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.ChannelMonitorTemplateResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ChannelMonitorTemplateRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public ChannelMonitorTemplateRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public List<ChannelMonitorTemplateResponse> list(String provider) {
        String sql = """
                select t.id, t.name, t.provider, t.description, t.extra_headers::text as extra_headers_json,
                       t.body_override_mode, t.body_override::text as body_override_json,
                       t.created_at, t.updated_at,
                       coalesce((
                           select count(*) from channel_monitors m where m.template_id = t.id
                       ), 0) as associated_monitors
                from channel_monitor_request_templates t
                where (:provider is null or t.provider = :provider)
                order by t.provider asc, t.name asc
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("provider", blankToNull(provider)), templateRowMapper());
    }

    public Optional<ChannelMonitorTemplateResponse> get(long id) {
        String sql = """
                select t.id, t.name, t.provider, t.description, t.extra_headers::text as extra_headers_json,
                       t.body_override_mode, t.body_override::text as body_override_json,
                       t.created_at, t.updated_at,
                       coalesce((
                           select count(*) from channel_monitors m where m.template_id = t.id
                       ), 0) as associated_monitors
                from channel_monitor_request_templates t
                where t.id = :id
                """;
        List<ChannelMonitorTemplateResponse> rows =
                jdbcTemplate.query(sql, new MapSqlParameterSource("id", id), templateRowMapper());
        return rows.stream().findFirst();
    }

    public ChannelMonitorTemplateResponse create(
            String name,
            String provider,
            String description,
            Map<String, String> extraHeaders,
            String bodyOverrideMode,
            Map<String, Object> bodyOverride
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("provider", provider)
                .addValue("description", description)
                .addValue("extraHeaders", jsonHelper.writeJson(extraHeaders == null ? Map.of() : extraHeaders))
                .addValue("bodyOverrideMode", bodyOverrideMode)
                .addValue("bodyOverride", bodyOverride == null ? null : jsonHelper.writeJson(bodyOverride));
        jdbcTemplate.update("""
                insert into channel_monitor_request_templates (
                    name, provider, description, extra_headers, body_override_mode, body_override, created_at, updated_at
                ) values (
                    :name, :provider, :description, cast(:extraHeaders as jsonb), :bodyOverrideMode,
                    cast(:bodyOverride as jsonb), now(), now()
                )
                """, params, keyHolder, new String[]{"id"});
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("failed to create channel monitor template");
        }
        return get(id.longValue()).orElseThrow();
    }

    public ChannelMonitorTemplateResponse update(
            long id,
            String name,
            String description,
            Map<String, String> extraHeaders,
            String bodyOverrideMode,
            Map<String, Object> bodyOverride
    ) {
        jdbcTemplate.update("""
                update channel_monitor_request_templates
                set name = :name,
                    description = :description,
                    extra_headers = cast(:extraHeaders as jsonb),
                    body_override_mode = :bodyOverrideMode,
                    body_override = cast(:bodyOverride as jsonb),
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("description", description)
                .addValue("extraHeaders", jsonHelper.writeJson(extraHeaders == null ? Map.of() : extraHeaders))
                .addValue("bodyOverrideMode", bodyOverrideMode)
                .addValue("bodyOverride", bodyOverride == null ? null : jsonHelper.writeJson(bodyOverride)));
        return get(id).orElseThrow();
    }

    public int delete(long id) {
        return jdbcTemplate.update("""
                delete from channel_monitor_request_templates
                where id = :id
                """, new MapSqlParameterSource("id", id));
    }

    public long applyToMonitors(long templateId, List<Long> monitorIds) {
        if (monitorIds == null || monitorIds.isEmpty()) {
            return 0L;
        }
        ChannelMonitorTemplateResponse template = get(templateId).orElseThrow();
        return jdbcTemplate.update("""
                update channel_monitors
                set extra_headers = cast(:extraHeaders as jsonb),
                    body_override_mode = :bodyOverrideMode,
                    body_override = cast(:bodyOverride as jsonb),
                    updated_at = now()
                where template_id = :templateId
                  and id in (:monitorIds)
                """, new MapSqlParameterSource()
                .addValue("templateId", templateId)
                .addValue("monitorIds", monitorIds)
                .addValue("extraHeaders", jsonHelper.writeJson(template.extra_headers()))
                .addValue("bodyOverrideMode", template.body_override_mode())
                .addValue("bodyOverride", template.body_override() == null ? null : jsonHelper.writeJson(template.body_override())));
    }

    public List<AssociatedMonitorBriefResponse> listAssociatedMonitors(long templateId) {
        return jdbcTemplate.query("""
                select id, name, provider, enabled
                from channel_monitors
                where template_id = :templateId
                order by name asc
                """, new MapSqlParameterSource("templateId", templateId), associatedMonitorRowMapper());
    }

    public boolean isDuplicateName(DuplicateKeyException ex) {
        return ex != null;
    }

    private RowMapper<ChannelMonitorTemplateResponse> templateRowMapper() {
        return (rs, rowNum) -> new ChannelMonitorTemplateResponse(
                rs.getLong("id"),
                defaultString(rs.getString("name")),
                defaultString(rs.getString("provider")),
                defaultString(rs.getString("description")),
                readStringMap(rs.getString("extra_headers_json")),
                defaultString(rs.getString("body_override_mode")),
                readObjectMapOrNull(rs.getString("body_override_json")),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at")),
                rs.getLong("associated_monitors")
        );
    }

    private RowMapper<AssociatedMonitorBriefResponse> associatedMonitorRowMapper() {
        return (rs, rowNum) -> new AssociatedMonitorBriefResponse(
                rs.getLong("id"),
                defaultString(rs.getString("name")),
                defaultString(rs.getString("provider")),
                rs.getBoolean("enabled")
        );
    }

    private Map<String, String> readStringMap(String raw) {
        Map<String, Object> values = jsonHelper.readObjectMap(raw);
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                result.put(key, String.valueOf(value));
            }
        });
        return result;
    }

    private Map<String, Object> readObjectMapOrNull(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return jsonHelper.readObjectMap(raw);
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
