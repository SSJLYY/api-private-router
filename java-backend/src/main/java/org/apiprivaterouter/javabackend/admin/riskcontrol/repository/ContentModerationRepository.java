package org.apiprivaterouter.javabackend.admin.riskcontrol.repository;

import org.apiprivaterouter.javabackend.admin.riskcontrol.model.ContentModerationLogResponse;
import org.apiprivaterouter.javabackend.admin.riskcontrol.service.ContentModerationLogCommand;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.riskcontrol.runtime.model.ContentModerationLogRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ContentModerationRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public ContentModerationRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public PageResponse<ContentModerationLogResponse> listLogs(
            int page,
            int pageSize,
            String result,
            Long groupId,
            String endpoint,
            String search,
            String from,
            String to
    ) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        int offset = (normalizedPage - 1) * normalizedPageSize;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("groupId", groupId)
                .addValue("endpoint", blankToNull(endpoint))
                .addValue("search", blankToNull(search))
                .addValue("likeSearch", blankToNull(search) == null ? null : "%" + search.trim() + "%")
                .addValue("fromTs", blankToNull(from))
                .addValue("toTs", blankToNull(to))
                .addValue("pageSize", normalizedPageSize)
                .addValue("offset", offset);

        String where = buildWhereClause(result);
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from content_moderation_logs l
                left join users u on u.id = l.user_id
                """ + where, params, Long.class);

        List<ContentModerationLogResponse> items = jdbcTemplate.query("""
                select l.id, l.request_id, l.user_id, l.user_email, l.api_key_id, l.api_key_name, l.group_id, l.group_name,
                       l.endpoint, l.provider, l.model, l.mode, l.action, l.flagged, l.highest_category, l.highest_score,
                       l.category_scores::text as category_scores_json,
                       l.threshold_snapshot::text as threshold_snapshot_json,
                       l.input_excerpt, l.upstream_latency_ms, l.error, l.violation_count, l.auto_banned, l.email_sent,
                       coalesce(u.status, '') as user_status, l.queue_delay_ms, l.created_at
                from content_moderation_logs l
                left join users u on u.id = l.user_id
                """ + where + """
                order by l.created_at desc, l.id desc
                limit :pageSize offset :offset
                """, params, logRowMapper());

        return new PageResponse<>(items, total == null ? 0L : total, normalizedPage, normalizedPageSize);
    }

    public ContentModerationLogResponse createLog(
            ContentModerationLogCommand command,
            int violationCount,
            boolean autoBanned,
            String userStatus
    ) {
        Long id = insertLog(new MapSqlParameterSource()
                .addValue("requestId", defaultString(command.requestId()))
                .addValue("userId", command.userId())
                .addValue("userEmail", defaultString(command.userEmail()))
                .addValue("apiKeyId", command.apiKeyId())
                .addValue("apiKeyName", defaultString(command.apiKeyName()))
                .addValue("groupId", command.groupId())
                .addValue("groupName", defaultString(command.groupName()))
                .addValue("endpoint", defaultString(command.endpoint()))
                .addValue("provider", defaultString(command.provider()))
                .addValue("model", defaultString(command.model()))
                .addValue("mode", defaultString(command.mode()))
                .addValue("action", defaultString(command.action()))
                .addValue("flagged", command.flagged())
                .addValue("highestCategory", defaultString(command.highestCategory()))
                .addValue("highestScore", command.highestScore())
                .addValue("categoryScores", jsonHelper.writeJson(command.categoryScores() == null ? Map.of() : command.categoryScores()))
                .addValue("thresholdSnapshot", jsonHelper.writeJson(command.thresholdSnapshot() == null ? Map.of() : command.thresholdSnapshot()))
                .addValue("inputExcerpt", defaultString(command.inputExcerpt()))
                .addValue("upstreamLatencyMs", command.upstreamLatencyMs())
                .addValue("error", defaultString(command.error()))
                .addValue("violationCount", Math.max(0, violationCount))
                .addValue("autoBanned", autoBanned)
                .addValue("emailSent", command.emailSent())
                .addValue("queueDelayMs", command.queueDelayMs()));

        if (id == null) {
            throw new IllegalStateException("failed to insert content moderation log");
        }
        return jdbcTemplate.queryForObject("""
                select l.id, l.request_id, l.user_id, l.user_email, l.api_key_id, l.api_key_name, l.group_id, l.group_name,
                       l.endpoint, l.provider, l.model, l.mode, l.action, l.flagged, l.highest_category, l.highest_score,
                       l.category_scores::text as category_scores_json,
                       l.threshold_snapshot::text as threshold_snapshot_json,
                       l.input_excerpt, l.upstream_latency_ms, l.error, l.violation_count, l.auto_banned, l.email_sent,
                       coalesce(u.status, :userStatus) as user_status, l.queue_delay_ms, l.created_at
                from content_moderation_logs l
                left join users u on u.id = l.user_id
                where l.id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userStatus", defaultString(userStatus)), logRowMapper());
    }

    public void createLog(ContentModerationLogRecord log) {
        if (log == null) {
            return;
        }
        insertLog(new MapSqlParameterSource()
                .addValue("requestId", defaultString(log.requestId()))
                .addValue("userId", log.userId())
                .addValue("userEmail", defaultString(log.userEmail()))
                .addValue("apiKeyId", log.apiKeyId())
                .addValue("apiKeyName", defaultString(log.apiKeyName()))
                .addValue("groupId", log.groupId())
                .addValue("groupName", defaultString(log.groupName()))
                .addValue("endpoint", defaultString(log.endpoint()))
                .addValue("provider", defaultString(log.provider()))
                .addValue("model", defaultString(log.model()))
                .addValue("mode", defaultString(log.mode()))
                .addValue("action", defaultString(log.action()))
                .addValue("flagged", log.flagged())
                .addValue("highestCategory", defaultString(log.highestCategory()))
                .addValue("highestScore", log.highestScore())
                .addValue("categoryScores", jsonHelper.writeJson(log.categoryScores() == null ? Map.of() : log.categoryScores()))
                .addValue("thresholdSnapshot", jsonHelper.writeJson(log.thresholdSnapshot() == null ? Map.of() : log.thresholdSnapshot()))
                .addValue("inputExcerpt", defaultString(log.inputExcerpt()))
                .addValue("upstreamLatencyMs", log.upstreamLatencyMs())
                .addValue("error", defaultString(log.error()))
                .addValue("violationCount", Math.max(0, log.violationCount()))
                .addValue("autoBanned", log.autoBanned())
                .addValue("emailSent", log.emailSent())
                .addValue("queueDelayMs", log.queueDelayMs()));
    }

    public Optional<String> findUserStatus(long userId) {
        List<String> rows = jdbcTemplate.query("""
                select status
                from users
                where id = :userId
                  and deleted_at is null
                limit 1
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> rs.getString("status"));
        return rows.stream().findFirst();
    }

    public void activateUser(long userId) {
        jdbcTemplate.update("""
                update users
                set status = 'active',
                    updated_at = now()
                where id = :userId
                  and deleted_at is null
                """, new MapSqlParameterSource("userId", userId));
    }

    public void disableUser(long userId) {
        jdbcTemplate.update("""
                update users
                set status = 'disabled',
                    updated_at = now()
                where id = :userId
                  and deleted_at is null
                """, new MapSqlParameterSource("userId", userId));
    }

    public int countFlaggedByUserSince(long userId, Instant since) {
        if (userId <= 0) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject("""
                with last_auto_ban as (
                    select max(created_at) as at
                    from content_moderation_logs
                    where user_id = :userId
                      and auto_banned = true
                )
                select count(*)
                from content_moderation_logs
                where user_id = :userId
                  and flagged = true
                  and created_at >= cast(:sinceTs as timestamptz)
                  and created_at > coalesce((select at from last_auto_ban), '-infinity'::timestamptz)
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sinceTs", since == null ? null : since.toString()), Integer.class);
        return count == null ? 0 : count;
    }

    public CleanupResult cleanupExpiredLogs(Instant hitBefore, Instant nonHitBefore) {
        long deletedHit = 0;
        if (hitBefore != null) {
            int rows = jdbcTemplate.update("""
                    delete from content_moderation_logs
                    where flagged = true
                      and created_at < cast(:hitBefore as timestamptz)
                    """, new MapSqlParameterSource("hitBefore", hitBefore.toString()));
            deletedHit = rows;
        }

        long deletedNonHit = 0;
        if (nonHitBefore != null) {
            int rows = jdbcTemplate.update("""
                    delete from content_moderation_logs
                    where flagged = false
                      and created_at < cast(:nonHitBefore as timestamptz)
                    """, new MapSqlParameterSource("nonHitBefore", nonHitBefore.toString()));
            deletedNonHit = rows;
        }

        return new CleanupResult(Instant.now(), deletedHit, deletedNonHit);
    }

    private Long insertLog(MapSqlParameterSource params) {
        return jdbcTemplate.queryForObject("""
                insert into content_moderation_logs (
                    request_id, user_id, user_email, api_key_id, api_key_name, group_id, group_name,
                    endpoint, provider, model, mode, action, flagged, highest_category, highest_score,
                    category_scores, threshold_snapshot, input_excerpt, upstream_latency_ms, error,
                    violation_count, auto_banned, email_sent, queue_delay_ms
                ) values (
                    :requestId, :userId, :userEmail, :apiKeyId, :apiKeyName, :groupId, :groupName,
                    :endpoint, :provider, :model, :mode, :action, :flagged, :highestCategory, :highestScore,
                    cast(:categoryScores as jsonb), cast(:thresholdSnapshot as jsonb), :inputExcerpt, :upstreamLatencyMs, :error,
                    :violationCount, :autoBanned, :emailSent, :queueDelayMs
                )
                returning id
                """, params, Long.class);
    }

    private String buildWhereClause(String result) {
        List<String> conditions = new ArrayList<>();
        conditions.add("l.id is not null");

        String normalizedResult = result == null ? "" : result.trim().toLowerCase();
        switch (normalizedResult) {
            case "hit", "flagged" -> conditions.add("l.flagged = true");
            case "blocked", "block" -> conditions.add("l.action = 'block'");
            case "pass", "allow" -> conditions.add("l.flagged = false and l.error = ''");
            case "error" -> conditions.add("l.error <> ''");
            default -> {
            }
        }

        conditions.add("(:groupId is null or l.group_id = :groupId)");
        conditions.add("(:endpoint is null or l.endpoint = :endpoint)");
        conditions.add("""
                (:search is null or (
                    l.request_id ilike :likeSearch
                    or l.user_email ilike :likeSearch
                    or l.api_key_name ilike :likeSearch
                    or l.model ilike :likeSearch
                    or l.input_excerpt ilike :likeSearch
                ))
                """);
        conditions.add("(:fromTs is null or l.created_at >= cast(:fromTs as timestamptz))");
        conditions.add("(:toTs is null or l.created_at <= cast(:toTs as timestamptz))");
        return "\nwhere " + String.join("\n  and ", conditions) + "\n";
    }

    private RowMapper<ContentModerationLogResponse> logRowMapper() {
        return (rs, rowNum) -> new ContentModerationLogResponse(
                rs.getLong("id"),
                defaultString(rs.getString("request_id")),
                rs.getObject("user_id", Long.class),
                defaultString(rs.getString("user_email")),
                rs.getObject("api_key_id", Long.class),
                defaultString(rs.getString("api_key_name")),
                rs.getObject("group_id", Long.class),
                defaultString(rs.getString("group_name")),
                defaultString(rs.getString("endpoint")),
                defaultString(rs.getString("provider")),
                defaultString(rs.getString("model")),
                defaultString(rs.getString("mode")),
                defaultString(rs.getString("action")),
                rs.getBoolean("flagged"),
                defaultString(rs.getString("highest_category")),
                rs.getDouble("highest_score"),
                readDoubleMap(rs.getString("category_scores_json")),
                readDoubleMap(rs.getString("threshold_snapshot_json")),
                defaultString(rs.getString("input_excerpt")),
                rs.getObject("upstream_latency_ms", Integer.class),
                defaultString(rs.getString("error")),
                rs.getInt("violation_count"),
                rs.getBoolean("auto_banned"),
                rs.getBoolean("email_sent"),
                defaultString(rs.getString("user_status")),
                rs.getObject("queue_delay_ms", Integer.class),
                toIsoString(rs.getTimestamp("created_at"))
        );
    }

    private Map<String, Double> readDoubleMap(String raw) {
        Map<String, Object> source = jsonHelper.readObjectMap(raw);
        Map<String, Double> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value instanceof Number number) {
                result.put(key, number.doubleValue());
            }
        });
        return result;
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

    public record CleanupResult(
            Instant finishedAt,
            long deletedHit,
            long deletedNonHit
    ) {
    }
}
