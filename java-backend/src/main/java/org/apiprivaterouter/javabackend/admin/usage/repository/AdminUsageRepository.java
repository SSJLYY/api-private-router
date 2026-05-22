package org.apiprivaterouter.javabackend.admin.usage.repository;

import org.apiprivaterouter.javabackend.admin.usage.model.AdminUsageLogResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.AdminUsageStatsResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.SimpleUsageApiKeyResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.SimpleUsageUserResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.UsageCleanupTaskResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class AdminUsageRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AdminUsageRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public PageResponse<AdminUsageLogResponse> listUsageLogs(
            int page,
            int pageSize,
            UsageFilters filters,
            String sortBy,
            String sortOrder
    ) {
        int offset = Math.max(page - 1, 0) * pageSize;
        MapSqlParameterSource params = buildFilterParams(filters)
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        String where = buildWhereClause();
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from usage_logs ul
                """ + where, params, Long.class);

        List<AdminUsageLogResponse> items = jdbcTemplate.query("""
                select ul.id, ul.user_id, ul.api_key_id, ul.account_id, ul.request_id,
                       coalesce(nullif(trim(ul.requested_model), ''), ul.model) as display_model,
                       ul.upstream_model, ul.service_tier, ul.reasoning_effort,
                       ul.inbound_endpoint, ul.upstream_endpoint,
                       ul.group_id, ul.subscription_id,
                       ul.input_tokens, ul.output_tokens, ul.cache_creation_tokens, ul.cache_read_tokens,
                       ul.cache_creation_5m_tokens, ul.cache_creation_1h_tokens,
                       ul.input_cost, ul.output_cost, ul.cache_creation_cost, ul.cache_read_cost,
                       ul.total_cost, ul.actual_cost, ul.rate_multiplier,
                       ul.account_rate_multiplier, ul.account_stats_cost,
                       ul.billing_type, ul.request_type, ul.stream, ul.openai_ws_mode,
                       ul.duration_ms, ul.first_token_ms, ul.image_count, ul.image_size,
                       ul.user_agent, ul.cache_ttl_overridden, ul.billing_mode,
                       ul.channel_id, ul.model_mapping_chain, ul.billing_tier,
                       ul.ip_address, ul.created_at,
                       coalesce(u.email, '') as user_email,
                       coalesce(u.username, '') as user_username,
                       coalesce(k.name, '') as api_key_name,
                       coalesce(g.name, '') as group_name,
                       coalesce(a.name, '') as account_name
                from usage_logs ul
                left join users u on u.id = ul.user_id
                left join api_keys k on k.id = ul.api_key_id
                left join groups g on g.id = ul.group_id
                left join accounts a on a.id = ul.account_id
                """ + where + buildOrderBy(sortBy, sortOrder) + """
                limit :pageSize offset :offset
                """, params, (rs, rowNum) -> mapUsageLog(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public AdminUsageStatsResponse getStats(UsageFilters filters) {
        MapSqlParameterSource params = buildFilterParams(filters);
        String where = buildWhereClause();
        AdminUsageStatsResponse summary = jdbcTemplate.queryForObject("""
                select
                    count(*) as total_requests,
                    coalesce(sum(ul.input_tokens), 0) as total_input_tokens,
                    coalesce(sum(ul.output_tokens), 0) as total_output_tokens,
                    coalesce(sum(ul.cache_creation_tokens + ul.cache_read_tokens), 0) as total_cache_tokens,
                    coalesce(sum(ul.input_tokens + ul.output_tokens + ul.cache_creation_tokens + ul.cache_read_tokens), 0) as total_tokens,
                    coalesce(sum(ul.total_cost), 0) as total_cost,
                    coalesce(sum(ul.actual_cost), 0) as total_actual_cost,
                    coalesce(sum(coalesce(ul.account_stats_cost, ul.total_cost) * coalesce(ul.account_rate_multiplier, 1)), 0) as total_account_cost,
                    coalesce(avg(ul.duration_ms), 0) as avg_duration_ms
                from usage_logs ul
                """ + where, params, (rs, rowNum) -> new AdminUsageStatsResponse(
                rs.getLong("total_requests"),
                rs.getLong("total_input_tokens"),
                rs.getLong("total_output_tokens"),
                rs.getLong("total_cache_tokens"),
                rs.getLong("total_tokens"),
                rs.getDouble("total_cost"),
                rs.getDouble("total_actual_cost"),
                rs.getDouble("total_account_cost"),
                rs.getDouble("avg_duration_ms"),
                List.of(),
                List.of(),
                List.of()
        ));
        if (summary == null) {
            return new AdminUsageStatsResponse(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
        }
        return new AdminUsageStatsResponse(
                summary.total_requests(),
                summary.total_input_tokens(),
                summary.total_output_tokens(),
                summary.total_cache_tokens(),
                summary.total_tokens(),
                summary.total_cost(),
                summary.total_actual_cost(),
                summary.total_account_cost(),
                summary.average_duration_ms(),
                getEndpointStats(filters, "ul.inbound_endpoint"),
                getEndpointStats(filters, "ul.upstream_endpoint"),
                getEndpointPathStats(filters)
        );
    }

    public List<SimpleUsageUserResponse> searchUsers(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                select id, coalesce(email, '') as email
                from users
                where deleted_at is null
                  and email ilike :keyword
                order by email asc, id asc
                limit 30
                """, new MapSqlParameterSource("keyword", "%" + normalized + "%"), (rs, rowNum) -> new SimpleUsageUserResponse(
                rs.getLong("id"),
                rs.getString("email")
        ));
    }

    public List<SimpleUsageApiKeyResponse> searchApiKeys(Long userId, String keyword) {
        String normalized = keyword == null ? null : keyword.trim();
        return jdbcTemplate.query("""
                select id, coalesce(name, '') as name, user_id
                from api_keys
                where deleted_at is null
                  and (:userId is null or user_id = :userId)
                  and (:keyword is null or :keyword = '' or name ilike :likeKeyword)
                order by name asc, id asc
                limit 30
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("keyword", normalized)
                .addValue("likeKeyword", normalized == null || normalized.isBlank() ? null : "%" + normalized + "%"), (rs, rowNum) -> new SimpleUsageApiKeyResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getLong("user_id")
        ));
    }

    public PageResponse<UsageCleanupTask> listCleanupTasks(int page, int pageSize) {
        int offset = Math.max(page - 1, 0) * pageSize;
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from usage_cleanup_tasks
                """, new MapSqlParameterSource(), Long.class);
        List<UsageCleanupTask> items = jdbcTemplate.query("""
                select id, status, filters::text as filters, created_by, deleted_rows,
                       error_message, canceled_by, canceled_at, started_at, finished_at,
                       created_at, updated_at
                from usage_cleanup_tasks
                order by created_at desc, id desc
                limit :pageSize offset :offset
                """, new MapSqlParameterSource()
                .addValue("pageSize", pageSize)
                .addValue("offset", offset), (rs, rowNum) -> mapCleanupTask(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public UsageCleanupTask createCleanupTask(CleanupTaskFilters filters, long createdBy) {
        return jdbcTemplate.queryForObject("""
                insert into usage_cleanup_tasks(status, filters, created_by, deleted_rows)
                values (:status, cast(:filters as jsonb), :createdBy, 0)
                returning id, status, filters::text as filters, created_by, deleted_rows,
                          error_message, canceled_by, canceled_at, started_at, finished_at,
                          created_at, updated_at
                """, new MapSqlParameterSource()
                .addValue("status", CleanupTaskStatus.PENDING.value())
                .addValue("filters", jsonHelper.writeJson(filters.toResponseFilters()))
                .addValue("createdBy", createdBy), (rs, rowNum) -> mapCleanupTask(rs));
    }

    public UsageCleanupTask claimNextCleanupTask(long staleRunningAfterSeconds) {
        List<UsageCleanupTask> tasks = jdbcTemplate.query("""
                with next_task as (
                    select id
                    from usage_cleanup_tasks
                    where status = :pending
                       or (
                           status = :running
                           and started_at is not null
                           and started_at < now() - make_interval(secs => :staleSeconds)
                       )
                    order by created_at asc, id asc
                    limit 1
                    for update skip locked
                )
                update usage_cleanup_tasks task
                set status = :running,
                    started_at = now(),
                    finished_at = null,
                    error_message = null,
                    updated_at = now()
                from next_task
                where task.id = next_task.id
                returning task.id, task.status, task.filters::text as filters, task.created_by, task.deleted_rows,
                          task.error_message, task.canceled_by, task.canceled_at, task.started_at, task.finished_at,
                          task.created_at, task.updated_at
                """, new MapSqlParameterSource()
                .addValue("pending", CleanupTaskStatus.PENDING.value())
                .addValue("running", CleanupTaskStatus.RUNNING.value())
                .addValue("staleSeconds", Math.max(staleRunningAfterSeconds, 1L)), (rs, rowNum) -> mapCleanupTask(rs));
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    public String getCleanupTaskStatus(long taskId) {
        List<String> statuses = jdbcTemplate.query("""
                select status
                from usage_cleanup_tasks
                where id = :id
                """, new MapSqlParameterSource("id", taskId), (rs, rowNum) -> rs.getString("status"));
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    public boolean cancelCleanupTask(long taskId, long canceledBy) {
        Integer updated = jdbcTemplate.query("""
                update usage_cleanup_tasks
                set status = :status,
                    canceled_by = :canceledBy,
                    canceled_at = now(),
                    finished_at = now(),
                    error_message = null,
                    updated_at = now()
                where id = :id
                  and status in (:pending, :running)
                returning 1
                """, new MapSqlParameterSource()
                .addValue("status", CleanupTaskStatus.CANCELED.value())
                .addValue("canceledBy", canceledBy)
                .addValue("id", taskId)
                .addValue("pending", CleanupTaskStatus.PENDING.value())
                .addValue("running", CleanupTaskStatus.RUNNING.value()), (rs, rowNum) -> 1).stream().findFirst().orElse(null);
        return updated != null;
    }

    public void updateCleanupTaskProgress(long taskId, long deletedRows) {
        jdbcTemplate.update("""
                update usage_cleanup_tasks
                set deleted_rows = :deletedRows,
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("deletedRows", deletedRows)
                .addValue("id", taskId));
    }

    public void markCleanupTaskSucceeded(long taskId, long deletedRows) {
        jdbcTemplate.update("""
                update usage_cleanup_tasks
                set status = :status,
                    deleted_rows = :deletedRows,
                    finished_at = now(),
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("status", CleanupTaskStatus.SUCCEEDED.value())
                .addValue("deletedRows", deletedRows)
                .addValue("id", taskId));
    }

    public void markCleanupTaskFailed(long taskId, long deletedRows, String errorMessage) {
        jdbcTemplate.update("""
                update usage_cleanup_tasks
                set status = :status,
                    deleted_rows = :deletedRows,
                    error_message = :errorMessage,
                    finished_at = now(),
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("status", CleanupTaskStatus.FAILED.value())
                .addValue("deletedRows", deletedRows)
                .addValue("errorMessage", truncateError(errorMessage))
                .addValue("id", taskId));
    }

    public long deleteUsageLogsBatch(CleanupTaskFilters filters, int limit) {
        MapSqlParameterSource params = buildCleanupDeleteParams(filters)
                .addValue("limit", Math.max(limit, 1));
        Integer deleted = jdbcTemplate.query("""
                with target as (
                    select id
                    from usage_logs
                    """ + buildCleanupDeleteWhereClause() + """
                    order by created_at asc, id asc
                    limit :limit
                ),
                deleted_rows as (
                    delete from usage_logs
                    where id in (select id from target)
                    returning id
                )
                select count(*) as deleted_count
                from deleted_rows
                """, params, (rs, rowNum) -> rs.getInt("deleted_count")).stream().findFirst().orElse(0);
        return deleted == null ? 0 : deleted.longValue();
    }

    private List<AdminUsageStatsResponse.EndpointStat> getEndpointStats(UsageFilters filters, String endpointColumn) {
        MapSqlParameterSource params = buildFilterParams(filters);
        String where = buildWhereClause();
        String sql = """
                select
                    coalesce(nullif(trim(%s), ''), 'unknown') as endpoint,
                    count(*) as requests,
                    coalesce(sum(ul.input_tokens + ul.output_tokens + ul.cache_creation_tokens + ul.cache_read_tokens), 0) as total_tokens,
                    coalesce(sum(ul.total_cost), 0) as cost,
                    coalesce(sum(ul.actual_cost), 0) as actual_cost
                from usage_logs ul
                """.formatted(endpointColumn) + where + """
                group by endpoint
                order by requests desc, endpoint asc
                """;
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> new AdminUsageStatsResponse.EndpointStat(
                rs.getString("endpoint"),
                rs.getLong("requests"),
                rs.getLong("total_tokens"),
                rs.getDouble("cost"),
                rs.getDouble("actual_cost")
        ));
    }

    private List<AdminUsageStatsResponse.EndpointStat> getEndpointPathStats(UsageFilters filters) {
        MapSqlParameterSource params = buildFilterParams(filters);
        String where = buildWhereClause();
        String sql = """
                select
                    concat(
                        coalesce(nullif(trim(ul.inbound_endpoint), ''), 'unknown'),
                        ' -> ',
                        coalesce(nullif(trim(ul.upstream_endpoint), ''), 'unknown')
                    ) as endpoint,
                    count(*) as requests,
                    coalesce(sum(ul.input_tokens + ul.output_tokens + ul.cache_creation_tokens + ul.cache_read_tokens), 0) as total_tokens,
                    coalesce(sum(ul.total_cost), 0) as cost,
                    coalesce(sum(ul.actual_cost), 0) as actual_cost
                from usage_logs ul
                """ + where + """
                group by endpoint
                order by requests desc, endpoint asc
                """;
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> new AdminUsageStatsResponse.EndpointStat(
                rs.getString("endpoint"),
                rs.getLong("requests"),
                rs.getLong("total_tokens"),
                rs.getDouble("cost"),
                rs.getDouble("actual_cost")
        ));
    }

    private String buildWhereClause() {
        return """
                where (:userId is null or ul.user_id = :userId)
                  and (:apiKeyId is null or ul.api_key_id = :apiKeyId)
                  and (:accountId is null or ul.account_id = :accountId)
                  and (:groupId is null or ul.group_id = :groupId)
                  and (:model is null or ul.model = :model)
                  and (:billingType is null or ul.billing_type = :billingType)
                  and (:billingMode is null or ul.billing_mode = :billingMode)
                  and (:startTime is null or ul.created_at >= :startTime)
                  and (:endTime is null or ul.created_at < :endTime)
                  and (
                        :requestType is null
                        or (
                            :requestType = 'sync'
                            and (ul.request_type = 1 or (ul.request_type = 0 and ul.stream = false and ul.openai_ws_mode = false))
                        )
                        or (
                            :requestType = 'stream'
                            and (ul.request_type = 2 or (ul.request_type = 0 and ul.stream = true and ul.openai_ws_mode = false))
                        )
                        or (
                            :requestType = 'ws_v2'
                            and (ul.request_type = 3 or (ul.request_type = 0 and ul.openai_ws_mode = true))
                        )
                        or (
                            :requestType = 'unknown'
                            and ul.request_type = 0
                        )
                  )
                  and (:requestType is not null or :stream is null or ul.stream = :stream)
                """;
    }

    private String buildCleanupDeleteWhereClause() {
        return """
                where created_at >= :startTime
                  and created_at <= :endTime
                  and (:userId is null or user_id = :userId)
                  and (:apiKeyId is null or api_key_id = :apiKeyId)
                  and (:accountId is null or account_id = :accountId)
                  and (:groupId is null or group_id = :groupId)
                  and (:model is null or model = :model)
                  and (:billingType is null or billing_type = :billingType)
                  and (
                        :requestType is null
                        or (
                            :requestType = 'sync'
                            and (request_type = 1 or (request_type = 0 and stream = false and openai_ws_mode = false))
                        )
                        or (
                            :requestType = 'stream'
                            and (request_type = 2 or (request_type = 0 and stream = true and openai_ws_mode = false))
                        )
                        or (
                            :requestType = 'ws_v2'
                            and (request_type = 3 or (request_type = 0 and openai_ws_mode = true))
                        )
                        or (
                            :requestType = 'unknown'
                            and request_type = 0
                        )
                  )
                  and (:requestType is not null or :stream is null or stream = :stream)
                """;
    }

    private MapSqlParameterSource buildFilterParams(UsageFilters filters) {
        return new MapSqlParameterSource()
                .addValue("userId", filters.userId())
                .addValue("apiKeyId", filters.apiKeyId())
                .addValue("accountId", filters.accountId())
                .addValue("groupId", filters.groupId())
                .addValue("model", blankToNull(filters.model()))
                .addValue("requestType", blankToNull(filters.requestType()))
                .addValue("stream", filters.stream())
                .addValue("billingType", filters.billingType())
                .addValue("billingMode", blankToNull(filters.billingMode()))
                .addValue("startTime", filters.startTime() == null ? null : Timestamp.from(filters.startTime()))
                .addValue("endTime", filters.endTime() == null ? null : Timestamp.from(filters.endTime()));
    }

    private MapSqlParameterSource buildCleanupDeleteParams(CleanupTaskFilters filters) {
        return new MapSqlParameterSource()
                .addValue("startTime", Timestamp.from(filters.startTime()))
                .addValue("endTime", Timestamp.from(filters.endTime()))
                .addValue("userId", filters.userId())
                .addValue("apiKeyId", filters.apiKeyId())
                .addValue("accountId", filters.accountId())
                .addValue("groupId", filters.groupId())
                .addValue("model", blankToNull(filters.model()))
                .addValue("requestType", blankToNull(filters.requestType()))
                .addValue("stream", filters.stream())
                .addValue("billingType", filters.billingType());
    }

    private String buildOrderBy(String sortBy, String sortOrder) {
        String direction = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
        String field = switch (sortBy == null ? "" : sortBy.trim()) {
            case "model" -> "display_model";
            case "created_at" -> "ul.created_at";
            default -> "ul.created_at";
        };
        return "\norder by " + field + " " + direction + ", ul.id desc\n";
    }

    private AdminUsageLogResponse mapUsageLog(ResultSet rs) throws SQLException {
        Long accountId = rs.getObject("account_id", Long.class);
        Long groupId = rs.getObject("group_id", Long.class);
        Long subscriptionId = rs.getObject("subscription_id", Long.class);
        Long channelId = rs.getObject("channel_id", Long.class);
        return new AdminUsageLogResponse(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("api_key_id"),
                accountId,
                defaultString(rs.getString("request_id")),
                defaultString(rs.getString("display_model")),
                rs.getString("upstream_model"),
                rs.getString("service_tier"),
                rs.getString("reasoning_effort"),
                rs.getString("inbound_endpoint"),
                rs.getString("upstream_endpoint"),
                groupId,
                subscriptionId,
                rs.getInt("input_tokens"),
                rs.getInt("output_tokens"),
                rs.getInt("cache_creation_tokens"),
                rs.getInt("cache_read_tokens"),
                rs.getInt("cache_creation_5m_tokens"),
                rs.getInt("cache_creation_1h_tokens"),
                rs.getDouble("input_cost"),
                rs.getDouble("output_cost"),
                rs.getDouble("cache_creation_cost"),
                rs.getDouble("cache_read_cost"),
                rs.getDouble("total_cost"),
                rs.getDouble("actual_cost"),
                rs.getDouble("rate_multiplier"),
                rs.getObject("account_rate_multiplier", Double.class),
                rs.getObject("account_stats_cost", Double.class),
                rs.getInt("billing_type"),
                resolveRequestType(rs.getInt("request_type"), rs.getBoolean("stream"), rs.getBoolean("openai_ws_mode")),
                rs.getBoolean("stream"),
                rs.getBoolean("openai_ws_mode"),
                rs.getObject("duration_ms", Integer.class),
                rs.getObject("first_token_ms", Integer.class),
                rs.getInt("image_count"),
                rs.getString("image_size"),
                rs.getString("user_agent"),
                rs.getBoolean("cache_ttl_overridden"),
                rs.getString("billing_mode"),
                channelId,
                rs.getString("model_mapping_chain"),
                rs.getString("billing_tier"),
                rs.getString("ip_address"),
                toIsoString(rs.getTimestamp("created_at")),
                new AdminUsageLogResponse.UserSummary(
                        rs.getLong("user_id"),
                        defaultString(rs.getString("user_email")),
                        defaultString(rs.getString("user_username"))
                ),
                new AdminUsageLogResponse.ApiKeySummary(
                        rs.getLong("api_key_id"),
                        defaultString(rs.getString("api_key_name")),
                        rs.getLong("user_id")
                ),
                groupId == null ? null : new AdminUsageLogResponse.GroupSummary(
                        groupId,
                        defaultString(rs.getString("group_name"))
                ),
                accountId == null ? null : new AdminUsageLogResponse.AccountSummary(
                        accountId,
                        defaultString(rs.getString("account_name"))
                )
        );
    }

    private UsageCleanupTask mapCleanupTask(ResultSet rs) throws SQLException {
        UsageCleanupTaskResponse.Filters filters = jsonHelper.readObject(rs.getString("filters"), UsageCleanupTaskResponse.Filters.class);
        if (filters == null) {
            filters = new UsageCleanupTaskResponse.Filters(null, null, null, null, null, null, null, null, null, null);
        }
        return new UsageCleanupTask(
                rs.getLong("id"),
                CleanupTaskStatus.fromValue(rs.getString("status")),
                new CleanupTaskFilters(
                        parseInstant(filters.start_time()),
                        parseInstant(filters.end_time()),
                        filters.user_id(),
                        filters.api_key_id(),
                        filters.account_id(),
                        filters.group_id(),
                        filters.model(),
                        filters.request_type(),
                        filters.stream(),
                        filters.billing_type()
                ),
                rs.getLong("created_by"),
                rs.getLong("deleted_rows"),
                rs.getString("error_message"),
                rs.getObject("canceled_by", Long.class),
                toInstant(rs.getTimestamp("canceled_at")),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("finished_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private String resolveRequestType(int requestType, boolean stream, boolean openaiWsMode) {
        return switch (requestType) {
            case 1 -> "sync";
            case 2 -> "stream";
            case 3 -> "ws_v2";
            default -> openaiWsMode ? "ws_v2" : (stream ? "stream" : "sync");
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private String truncateError(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        String normalized = errorMessage.trim();
        if (normalized.length() <= 500) {
            return normalized;
        }
        return normalized.substring(0, 500);
    }

    public record UsageFilters(
            Long userId,
            Long apiKeyId,
            Long accountId,
            Long groupId,
            String model,
            String requestType,
            Boolean stream,
            Integer billingType,
            String billingMode,
            Instant startTime,
            Instant endTime
    ) {
    }

    public record CleanupTaskFilters(
            Instant startTime,
            Instant endTime,
            Long userId,
            Long apiKeyId,
            Long accountId,
            Long groupId,
            String model,
            String requestType,
            Boolean stream,
            Integer billingType
    ) {
        public UsageCleanupTaskResponse.Filters toResponseFilters() {
            return new UsageCleanupTaskResponse.Filters(
                    startTime == null ? null : startTime.toString(),
                    endTime == null ? null : endTime.toString(),
                    userId,
                    apiKeyId,
                    accountId,
                    groupId,
                    model,
                    requestType,
                    stream,
                    billingType
            );
        }
    }

    public record UsageCleanupTask(
            long id,
            CleanupTaskStatus status,
            CleanupTaskFilters filters,
            long createdBy,
            long deletedRows,
            String errorMessage,
            Long canceledBy,
            Instant canceledAt,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public enum CleanupTaskStatus {
        PENDING("pending"),
        RUNNING("running"),
        SUCCEEDED("succeeded"),
        FAILED("failed"),
        CANCELED("canceled");

        private final String value;

        CleanupTaskStatus(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static CleanupTaskStatus fromValue(String value) {
            for (CleanupTaskStatus status : values()) {
                if (status.value.equalsIgnoreCase(value)) {
                    return status;
                }
            }
            return PENDING;
        }
    }
}
