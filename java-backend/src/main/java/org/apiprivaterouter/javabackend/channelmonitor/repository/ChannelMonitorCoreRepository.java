package org.apiprivaterouter.javabackend.channelmonitor.repository;

import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorAvailabilityStat;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorCheckResult;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorHistoryEntry;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorLatestStatus;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorRecord;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorWriteRequest;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class ChannelMonitorCoreRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public ChannelMonitorCoreRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public Optional<ChannelMonitorRecord> findById(long id) {
        List<ChannelMonitorRecord> rows = jdbcTemplate.query(baseSelect() + " where m.id = :id",
                new MapSqlParameterSource("id", id), this::mapMonitor);
        return rows.stream().findFirst();
    }

    public List<ChannelMonitorRecord> listEnabled() {
        return jdbcTemplate.query(baseSelect() + " where m.enabled = true order by m.id desc",
                new MapSqlParameterSource(), this::mapMonitor);
    }

    public List<ChannelMonitorRecord> listAll(String provider, Boolean enabled, String search, int offset, int limit) {
        StringBuilder sql = new StringBuilder(baseSelect()).append(" where 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("provider", provider)
                .addValue("enabled", enabled)
                .addValue("search", search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase() + "%")
                .addValue("offset", offset)
                .addValue("limit", limit);
        if (provider != null && !provider.isBlank()) {
            sql.append(" and m.provider = :provider");
        }
        if (enabled != null) {
            sql.append(" and m.enabled = :enabled");
        }
        if (search != null && !search.isBlank()) {
            sql.append(" and (lower(m.name) like :search or lower(m.group_name) like :search or lower(m.primary_model) like :search)");
        }
        sql.append(" order by m.id desc offset :offset limit :limit");
        return jdbcTemplate.query(sql.toString(), params, this::mapMonitor);
    }

    public long countAll(String provider, Boolean enabled, String search) {
        StringBuilder sql = new StringBuilder("select count(*) from channel_monitors m where 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("provider", provider)
                .addValue("enabled", enabled)
                .addValue("search", search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase() + "%");
        if (provider != null && !provider.isBlank()) {
            sql.append(" and m.provider = :provider");
        }
        if (enabled != null) {
            sql.append(" and m.enabled = :enabled");
        }
        if (search != null && !search.isBlank()) {
            sql.append(" and (lower(m.name) like :search or lower(m.group_name) like :search or lower(m.primary_model) like :search)");
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0L : count;
    }

    public ChannelMonitorRecord create(ChannelMonitorWriteRequest request) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into channel_monitors (
                    name, provider, endpoint, api_key_encrypted, primary_model, extra_models, group_name,
                    enabled, interval_seconds, created_by, template_id, extra_headers, body_override_mode, body_override, created_at, updated_at
                ) values (
                    :name, :provider, :endpoint, :apiKeyEncrypted, :primaryModel, cast(:extraModels as jsonb), :groupName,
                    :enabled, :intervalSeconds, :createdBy, :templateId, cast(:extraHeaders as jsonb), :bodyOverrideMode, cast(:bodyOverride as jsonb), now(), now()
                )
                """, new MapSqlParameterSource()
                .addValue("name", request.name())
                .addValue("provider", request.provider())
                .addValue("endpoint", request.endpoint())
                .addValue("apiKeyEncrypted", request.apiKeyPlaintext())
                .addValue("primaryModel", request.primaryModel())
                .addValue("extraModels", jsonHelper.writeJson(request.extraModels()))
                .addValue("groupName", request.groupName())
                .addValue("enabled", request.enabled())
                .addValue("intervalSeconds", request.intervalSeconds())
                .addValue("createdBy", request.createdBy())
                .addValue("templateId", request.templateId())
                .addValue("extraHeaders", jsonHelper.writeJson(request.extraHeaders()))
                .addValue("bodyOverrideMode", request.bodyOverrideMode())
                .addValue("bodyOverride", request.bodyOverride() == null ? null : jsonHelper.writeJson(request.bodyOverride())),
                keyHolder,
                new String[]{"id"});
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("failed to create channel monitor");
        }
        return findById(id.longValue()).orElseThrow();
    }

    public ChannelMonitorRecord update(long id, ChannelMonitorRecord record) {
        jdbcTemplate.update("""
                update channel_monitors
                set name = :name,
                    provider = :provider,
                    endpoint = :endpoint,
                    api_key_encrypted = :apiKeyEncrypted,
                    primary_model = :primaryModel,
                    extra_models = cast(:extraModels as jsonb),
                    group_name = :groupName,
                    enabled = :enabled,
                    interval_seconds = :intervalSeconds,
                    template_id = :templateId,
                    extra_headers = cast(:extraHeaders as jsonb),
                    body_override_mode = :bodyOverrideMode,
                    body_override = cast(:bodyOverride as jsonb),
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", record.name())
                .addValue("provider", record.provider())
                .addValue("endpoint", record.endpoint())
                .addValue("apiKeyEncrypted", record.apiKeyEncrypted())
                .addValue("primaryModel", record.primaryModel())
                .addValue("extraModels", jsonHelper.writeJson(record.extraModels()))
                .addValue("groupName", record.groupName())
                .addValue("enabled", record.enabled())
                .addValue("intervalSeconds", record.intervalSeconds())
                .addValue("templateId", record.templateId())
                .addValue("extraHeaders", jsonHelper.writeJson(record.extraHeaders()))
                .addValue("bodyOverrideMode", record.bodyOverrideMode())
                .addValue("bodyOverride", record.bodyOverride() == null ? null : jsonHelper.writeJson(record.bodyOverride())));
        return findById(id).orElseThrow();
    }

    public void delete(long id) {
        jdbcTemplate.update("delete from channel_monitors where id = :id", new MapSqlParameterSource("id", id));
    }

    public void markChecked(long id, Instant checkedAt) {
        jdbcTemplate.update("""
                update channel_monitors
                set last_checked_at = :checkedAt,
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("checkedAt", Timestamp.from(checkedAt)));
    }

    public List<ChannelMonitorRecord> claimDueMonitors(Instant now, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query("""
                with due as (
                    select m.id
                    from channel_monitors m
                    where m.enabled = true
                      and (
                          m.last_checked_at is null
                          or m.last_checked_at + make_interval(secs => m.interval_seconds) <= :now
                      )
                    order by coalesce(m.last_checked_at, 'epoch'::timestamptz), m.id
                    limit :limit
                    for update skip locked
                )
                update channel_monitors m
                set last_checked_at = :now,
                    updated_at = now()
                from due
                where m.id = due.id
                returning m.id,
                          m.name,
                          m.provider,
                          m.endpoint,
                          m.api_key_encrypted,
                          m.primary_model,
                          m.extra_models::text as extra_models_json,
                          m.group_name,
                          m.enabled,
                          m.interval_seconds,
                          m.last_checked_at,
                          m.created_by,
                          m.created_at,
                          m.updated_at,
                          m.template_id,
                          m.extra_headers::text as extra_headers_json,
                          m.body_override_mode,
                          m.body_override::text as body_override_json
                """, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("limit", limit), this::mapMonitor);
    }

    public void insertHistory(long monitorId, List<ChannelMonitorCheckResult> results) {
        List<MapSqlParameterSource> batch = new ArrayList<>();
        for (ChannelMonitorCheckResult result : results) {
            batch.add(new MapSqlParameterSource()
                    .addValue("monitorId", monitorId)
                    .addValue("model", result.model())
                    .addValue("status", result.status())
                    .addValue("latencyMs", result.latencyMs())
                    .addValue("pingLatencyMs", result.pingLatencyMs())
                    .addValue("message", result.message() == null ? "" : result.message())
                    .addValue("checkedAt", Timestamp.from(result.checkedAt())));
        }
        jdbcTemplate.batchUpdate("""
                insert into channel_monitor_histories (
                    monitor_id, model, status, latency_ms, ping_latency_ms, message, checked_at
                ) values (
                    :monitorId, :model, :status, :latencyMs, :pingLatencyMs, :message, :checkedAt
                )
                """, batch.toArray(MapSqlParameterSource[]::new));
    }

    public List<ChannelMonitorHistoryEntry> findHistory(long monitorId, String model, int limit) {
        StringBuilder sql = new StringBuilder("""
                select id, monitor_id, model, status, latency_ms, ping_latency_ms, message, checked_at
                from channel_monitor_histories
                where monitor_id = :monitorId
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("monitorId", monitorId)
                .addValue("model", model == null || model.isBlank() ? null : model.trim())
                .addValue("limit", limit);
        if (model != null && !model.isBlank()) {
            sql.append(" and model = :model");
        }
        sql.append(" order by checked_at desc limit :limit");
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> mapHistory(rs));
    }

    public List<ChannelMonitorLatestStatus> findLatestStatuses(long monitorId) {
        return findLatestStatusesByMonitorIds(List.of(monitorId));
    }

    public List<ChannelMonitorLatestStatus> findLatestStatusesByMonitorIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                select distinct on (monitor_id, model)
                       monitor_id, model, status, latency_ms, ping_latency_ms, checked_at
                from channel_monitor_histories
                where monitor_id in (:ids)
                order by monitor_id, model, checked_at desc
                """, new MapSqlParameterSource("ids", ids), (rs, rowNum) -> new ChannelMonitorLatestStatus(
                rs.getLong("monitor_id"),
                rs.getString("model"),
                defaultString(rs.getString("status")),
                integerOrNull(rs, "latency_ms"),
                integerOrNull(rs, "ping_latency_ms"),
                toInstant(rs.getTimestamp("checked_at"))
        ));
    }

    public List<ChannelMonitorAvailabilityStat> findAvailability(long monitorId, int windowDays) {
        return findAvailabilityByMonitorIds(List.of(monitorId), windowDays);
    }

    public List<ChannelMonitorAvailabilityStat> findAvailabilityByMonitorIds(List<Long> ids, int windowDays) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                select monitor_id,
                       model,
                       count(*) as total_checks,
                       count(*) filter (where status in ('operational','degraded')) as operational_checks,
                       case when count(*) = 0 then 0
                            else count(*) filter (where status in ('operational','degraded'))::double precision * 100.0 / count(*) end as availability_pct,
                       case when count(latency_ms) = 0 then null
                            else round(avg(latency_ms))::int end as avg_latency_ms
                from channel_monitor_histories
                where monitor_id in (:ids)
                  and checked_at >= now() - make_interval(days => :windowDays)
                group by monitor_id, model
                """, new MapSqlParameterSource()
                .addValue("ids", ids)
                .addValue("windowDays", windowDays), (rs, rowNum) -> new ChannelMonitorAvailabilityStat(
                rs.getLong("monitor_id"),
                rs.getString("model"),
                windowDays,
                rs.getInt("total_checks"),
                rs.getInt("operational_checks"),
                rs.getDouble("availability_pct"),
                integerOrNull(rs, "avg_latency_ms")
        ));
    }

    public List<ChannelMonitorHistoryEntry> findRecentPrimaryHistory(List<Long> ids, Map<Long, String> primaryModelById, int perMonitorLimit) {
        if (ids == null || ids.isEmpty() || primaryModelById == null || primaryModelById.isEmpty()) {
            return List.of();
        }
        List<Long> targetIds = ids.stream().filter(primaryModelById::containsKey).toList();
        List<String> models = targetIds.stream().map(primaryModelById::get).toList();
        if (targetIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                with targets as (
                    select unnest(cast(:ids as bigint[])) as monitor_id,
                           unnest(cast(:models as text[])) as model
                ),
                ranked as (
                    select h.id, h.monitor_id, h.model, h.status, h.latency_ms, h.ping_latency_ms, h.message, h.checked_at,
                           row_number() over (partition by h.monitor_id order by h.checked_at desc) as rn
                    from channel_monitor_histories h
                    join targets t
                      on t.monitor_id = h.monitor_id and t.model = h.model
                )
                select id, monitor_id, model, status, latency_ms, ping_latency_ms, message, checked_at
                from ranked
                where rn <= :perMonitorLimit
                order by monitor_id, checked_at desc
                """, new MapSqlParameterSource()
                .addValue("ids", "{" + targetIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + "}")
                .addValue("models", "{" + models.stream().map(this::escapeArrayText).collect(Collectors.joining(",")) + "}")
                .addValue("perMonitorLimit", perMonitorLimit), (rs, rowNum) -> mapHistory(rs));
    }

    public String findSettingValue(String key) {
        List<String> rows = jdbcTemplate.query("""
                select value
                from settings
                where key = :key
                limit 1
                """, new MapSqlParameterSource("key", key), (rs, rowNum) -> rs.getString("value"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String baseSelect() {
        return """
                select m.id,
                       m.name,
                       m.provider,
                       m.endpoint,
                       m.api_key_encrypted,
                       m.primary_model,
                       m.extra_models::text as extra_models_json,
                       m.group_name,
                       m.enabled,
                       m.interval_seconds,
                       m.last_checked_at,
                       m.created_by,
                       m.created_at,
                       m.updated_at,
                       m.template_id,
                       m.extra_headers::text as extra_headers_json,
                       m.body_override_mode,
                       m.body_override::text as body_override_json
                from channel_monitors m
                """;
    }

    private ChannelMonitorRecord mapMonitor(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChannelMonitorRecord(
                rs.getLong("id"),
                defaultString(rs.getString("name")),
                defaultString(rs.getString("provider")),
                defaultString(rs.getString("endpoint")),
                defaultString(rs.getString("api_key_encrypted")),
                defaultString(rs.getString("primary_model")),
                jsonHelper.readStringList(rs.getString("extra_models_json")),
                defaultString(rs.getString("group_name")),
                rs.getBoolean("enabled"),
                rs.getInt("interval_seconds"),
                toInstant(rs.getTimestamp("last_checked_at")),
                rs.getLong("created_by"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                longOrNull(rs, "template_id"),
                readStringMap(rs.getString("extra_headers_json")),
                defaultString(rs.getString("body_override_mode")),
                readObjectMapOrNull(rs.getString("body_override_json"))
        );
    }

    private ChannelMonitorHistoryEntry mapHistory(ResultSet rs) throws java.sql.SQLException {
        return new ChannelMonitorHistoryEntry(
                rs.getLong("id"),
                rs.getLong("monitor_id"),
                defaultString(rs.getString("model")),
                defaultString(rs.getString("status")),
                integerOrNull(rs, "latency_ms"),
                integerOrNull(rs, "ping_latency_ms"),
                defaultString(rs.getString("message")),
                toInstant(rs.getTimestamp("checked_at"))
        );
    }

    private Map<String, String> readStringMap(String raw) {
        Map<String, Object> values = jsonHelper.readObjectMap(raw);
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                out.put(key, String.valueOf(value));
            }
        });
        return out;
    }

    private Map<String, Object> readObjectMapOrNull(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return jsonHelper.readObjectMap(raw);
    }

    private Integer integerOrNull(ResultSet rs, String column) throws java.sql.SQLException {
        Integer value = (Integer) rs.getObject(column);
        return rs.wasNull() ? null : value;
    }

    private Long longOrNull(ResultSet rs, String column) throws java.sql.SQLException {
        Long value = (Long) rs.getObject(column);
        return rs.wasNull() ? null : value;
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String escapeArrayText(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
