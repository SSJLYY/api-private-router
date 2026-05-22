package org.apiprivaterouter.javabackend.userusage.repository;

import org.apiprivaterouter.javabackend.admin.dashboard.model.BatchApiKeysUsageResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.ModelStatsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.TrendResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.service.AdminDashboardService;
import org.apiprivaterouter.javabackend.admin.usage.model.AdminUsageLogResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.AdminUsageStatsResponse;
import org.apiprivaterouter.javabackend.admin.usage.repository.AdminUsageRepository;
import org.apiprivaterouter.javabackend.admin.usage.service.AdminUsageService;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.userusage.model.UserDashboardStatsResponse;
import org.apiprivaterouter.javabackend.userusage.model.UserUsageLogResponse;
import org.apiprivaterouter.javabackend.userusage.model.UserUsageStatsResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class UserUsageRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AdminUsageService adminUsageService;
    private final AdminDashboardService adminDashboardService;

    public UserUsageRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            AdminUsageService adminUsageService,
            AdminDashboardService adminDashboardService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.adminUsageService = adminUsageService;
        this.adminDashboardService = adminDashboardService;
    }

    public PageResponse<UserUsageLogResponse> listUsageLogs(
            long userId,
            int page,
            int pageSize,
            Long apiKeyId,
            String model,
            String requestType,
            Boolean stream,
            Integer billingType,
            String startDate,
            String endDate,
            String timezone,
            String sortBy,
            String sortOrder
    ) {
        PageResponse<AdminUsageLogResponse> raw = adminUsageService.listUsageLogs(
                page,
                pageSize,
                userId,
                apiKeyId,
                null,
                null,
                model,
                requestType,
                stream,
                billingType,
                null,
                startDate,
                endDate,
                timezone,
                sortBy,
                sortOrder
        );
        List<UserUsageLogResponse> items = raw.items().stream().map(this::toUserUsageLog).toList();
        return new PageResponse<>(items, raw.total(), raw.page(), raw.page_size(), raw.pages());
    }

    public Optional<UserUsageLogResponse> findUsageLogByIdForUser(long id, long userId) {
        List<UserUsageLogResponse> items = jdbcTemplate.query("""
                select ul.id, ul.user_id, ul.api_key_id, ul.account_id, ul.request_id,
                       coalesce(nullif(trim(ul.requested_model), ''), ul.model) as display_model,
                       ul.service_tier, ul.reasoning_effort,
                       ul.inbound_endpoint, ul.upstream_endpoint,
                       ul.group_id, ul.subscription_id,
                       ul.input_tokens, ul.output_tokens, ul.cache_creation_tokens, ul.cache_read_tokens,
                       ul.cache_creation_5m_tokens, ul.cache_creation_1h_tokens,
                       ul.input_cost, ul.output_cost, ul.cache_creation_cost, ul.cache_read_cost,
                       ul.total_cost, ul.actual_cost, ul.rate_multiplier,
                       ul.billing_type, ul.request_type, ul.stream, ul.openai_ws_mode,
                       ul.duration_ms, ul.first_token_ms, ul.image_count, ul.image_size,
                       ul.user_agent, ul.cache_ttl_overridden, ul.billing_mode, ul.created_at,
                       coalesce(u.email, '') as user_email,
                       coalesce(u.username, '') as user_username,
                       coalesce(k.name, '') as api_key_name,
                       coalesce(g.name, '') as group_name
                from usage_logs ul
                left join users u on u.id = ul.user_id
                left join api_keys k on k.id = ul.api_key_id
                left join groups g on g.id = ul.group_id
                where ul.id = :id
                  and ul.user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId), (rs, rowNum) -> new UserUsageLogResponse(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("api_key_id"),
                rs.getObject("account_id", Long.class),
                defaultString(rs.getString("request_id")),
                defaultString(rs.getString("display_model")),
                rs.getString("service_tier"),
                rs.getString("reasoning_effort"),
                rs.getString("inbound_endpoint"),
                rs.getString("upstream_endpoint"),
                rs.getObject("group_id", Long.class),
                rs.getObject("subscription_id", Long.class),
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
                toIsoString(rs.getTimestamp("created_at")),
                new UserUsageLogResponse.UserSummary(
                        rs.getLong("user_id"),
                        defaultString(rs.getString("user_email")),
                        defaultString(rs.getString("user_username"))
                ),
                new UserUsageLogResponse.ApiKeySummary(
                        rs.getLong("api_key_id"),
                        defaultString(rs.getString("api_key_name")),
                        rs.getLong("user_id")
                ),
                rs.getObject("group_id", Long.class) == null ? null : new UserUsageLogResponse.GroupSummary(
                        rs.getLong("group_id"),
                        defaultString(rs.getString("group_name"))
                )
        ));
        return items.stream().findFirst();
    }

    public UserUsageStatsResponse getStats(
            long userId,
            Long apiKeyId,
            String model,
            String requestType,
            Boolean stream,
            String period,
            String startDate,
            String endDate,
            String timezone,
            Integer billingType
    ) {
        AdminUsageStatsResponse raw = adminUsageService.getStats(
                userId,
                apiKeyId,
                null,
                null,
                model,
                requestType,
                stream,
                period,
                startDate,
                endDate,
                timezone,
                billingType,
                null
        );
        return new UserUsageStatsResponse(
                raw.total_requests(),
                raw.total_input_tokens(),
                raw.total_output_tokens(),
                raw.total_cache_tokens(),
                raw.total_tokens(),
                raw.total_cost(),
                raw.total_actual_cost(),
                raw.average_duration_ms()
        );
    }

    public UserDashboardStatsResponse getDashboardStats(long userId) {
        Instant now = Instant.now();
        ZonedDateTime zonedNow = ZonedDateTime.now();
        Instant todayStart = zonedNow.toLocalDate().atStartOfDay(zonedNow.getZone()).toInstant();
        Instant todayEnd = todayStart.plusSeconds(24 * 60 * 60);
        Instant fiveMinutesAgo = now.minusSeconds(5L * 60L);
        DashboardUsageStats usageStats = jdbcTemplate.queryForObject("""
                with scoped as (
                    select
                        created_at,
                        input_tokens,
                        output_tokens,
                        cache_creation_tokens,
                        cache_read_tokens,
                        total_cost,
                        actual_cost,
                        coalesce(duration_ms, 0) as duration_ms
                    from usage_logs
                    where user_id = :userId
                )
                select
                    count(*) as total_requests,
                    coalesce(sum(input_tokens), 0) as total_input_tokens,
                    coalesce(sum(output_tokens), 0) as total_output_tokens,
                    coalesce(sum(cache_creation_tokens), 0) as total_cache_creation_tokens,
                    coalesce(sum(cache_read_tokens), 0) as total_cache_read_tokens,
                    coalesce(sum(total_cost), 0) as total_cost,
                    coalesce(sum(actual_cost), 0) as total_actual_cost,
                    coalesce(sum(duration_ms), 0) as total_duration_ms,
                    count(*) filter (where created_at >= :todayStart and created_at < :todayEnd) as today_requests,
                    coalesce(sum(input_tokens) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_input_tokens,
                    coalesce(sum(output_tokens) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_output_tokens,
                    coalesce(sum(cache_creation_tokens) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_cache_creation_tokens,
                    coalesce(sum(cache_read_tokens) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_cache_read_tokens,
                    coalesce(sum(total_cost) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_cost,
                    coalesce(sum(actual_cost) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_actual_cost,
                    count(*) filter (where created_at >= :fiveMinutesAgo) as requests_last_five_min,
                    coalesce(sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens) filter (where created_at >= :fiveMinutesAgo), 0) as tokens_last_five_min
                from scoped
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("todayStart", Timestamp.from(todayStart))
                .addValue("todayEnd", Timestamp.from(todayEnd))
                .addValue("fiveMinutesAgo", Timestamp.from(fiveMinutesAgo)), (rs, rowNum) -> new DashboardUsageStats(
                rs.getLong("total_requests"),
                rs.getLong("total_input_tokens"),
                rs.getLong("total_output_tokens"),
                rs.getLong("total_cache_creation_tokens"),
                rs.getLong("total_cache_read_tokens"),
                rs.getDouble("total_cost"),
                rs.getDouble("total_actual_cost"),
                rs.getLong("total_duration_ms"),
                rs.getLong("today_requests"),
                rs.getLong("today_input_tokens"),
                rs.getLong("today_output_tokens"),
                rs.getLong("today_cache_creation_tokens"),
                rs.getLong("today_cache_read_tokens"),
                rs.getDouble("today_cost"),
                rs.getDouble("today_actual_cost"),
                rs.getLong("requests_last_five_min"),
                rs.getLong("tokens_last_five_min")
        ));
        if (usageStats == null) {
            usageStats = DashboardUsageStats.empty();
        }
        ApiKeyStats apiKeyStats = jdbcTemplate.queryForObject("""
                select
                    count(*) as total_api_keys,
                    count(*) filter (
                        where coalesce(nullif(status, ''), 'active') = 'active'
                    ) as active_api_keys
                from api_keys
                where user_id = :userId
                  and deleted_at is null
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> new ApiKeyStats(
                rs.getLong("total_api_keys"),
                rs.getLong("active_api_keys")
        ));
        if (apiKeyStats == null) {
            apiKeyStats = new ApiKeyStats(0, 0);
        }
        long totalTokens = usageStats.total_input_tokens + usageStats.total_output_tokens
                + usageStats.total_cache_creation_tokens + usageStats.total_cache_read_tokens;
        long todayTokens = usageStats.today_input_tokens + usageStats.today_output_tokens
                + usageStats.today_cache_creation_tokens + usageStats.today_cache_read_tokens;
        double avgDuration = usageStats.total_requests == 0 ? 0.0 : (double) usageStats.total_duration_ms / usageStats.total_requests;
        return new UserDashboardStatsResponse(
                apiKeyStats.total_api_keys,
                apiKeyStats.active_api_keys,
                usageStats.total_requests,
                usageStats.total_input_tokens,
                usageStats.total_output_tokens,
                usageStats.total_cache_creation_tokens,
                usageStats.total_cache_read_tokens,
                totalTokens,
                usageStats.total_cost,
                usageStats.total_actual_cost,
                usageStats.today_requests,
                usageStats.today_input_tokens,
                usageStats.today_output_tokens,
                usageStats.today_cache_creation_tokens,
                usageStats.today_cache_read_tokens,
                todayTokens,
                usageStats.today_cost,
                usageStats.today_actual_cost,
                avgDuration,
                usageStats.requests_last_five_min / 5,
                usageStats.tokens_last_five_min / 5
        );
    }

    public TrendResponse getTrend(long userId, String startDate, String endDate, String granularity, String timezone) {
        return adminDashboardService.getUsageTrend(
                startDate,
                endDate,
                granularity,
                userId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                timezone
        );
    }

    public ModelStatsResponse getModelStats(long userId, String startDate, String endDate, String timezone) {
        return adminDashboardService.getModelStats(
                startDate,
                endDate,
                userId,
                null,
                null,
                null,
                null,
                null,
                null,
                "requested",
                timezone
        );
    }

    public BatchApiKeysUsageResponse getBatchApiKeysUsage(List<Long> apiKeyIds, String timezone) {
        return adminDashboardService.getBatchApiKeysUsage(apiKeyIds, timezone);
    }

    private UserUsageLogResponse toUserUsageLog(AdminUsageLogResponse raw) {
        return new UserUsageLogResponse(
                raw.id(),
                raw.user_id(),
                raw.api_key_id(),
                raw.account_id(),
                raw.request_id(),
                raw.model(),
                raw.service_tier(),
                raw.reasoning_effort(),
                raw.inbound_endpoint(),
                raw.upstream_endpoint(),
                raw.group_id(),
                raw.subscription_id(),
                raw.input_tokens(),
                raw.output_tokens(),
                raw.cache_creation_tokens(),
                raw.cache_read_tokens(),
                raw.cache_creation_5m_tokens(),
                raw.cache_creation_1h_tokens(),
                raw.input_cost(),
                raw.output_cost(),
                raw.cache_creation_cost(),
                raw.cache_read_cost(),
                raw.total_cost(),
                raw.actual_cost(),
                raw.rate_multiplier(),
                raw.billing_type(),
                raw.request_type(),
                raw.stream(),
                raw.openai_ws_mode(),
                raw.duration_ms(),
                raw.first_token_ms(),
                raw.image_count(),
                raw.image_size(),
                raw.user_agent(),
                raw.cache_ttl_overridden(),
                raw.billing_mode(),
                raw.created_at(),
                raw.user() == null ? null : new UserUsageLogResponse.UserSummary(
                        raw.user().id(),
                        raw.user().email(),
                        raw.user().username()
                ),
                raw.api_key() == null ? null : new UserUsageLogResponse.ApiKeySummary(
                        raw.api_key().id(),
                        raw.api_key().name(),
                        raw.api_key().user_id()
                ),
                raw.group() == null ? null : new UserUsageLogResponse.GroupSummary(
                        raw.group().id(),
                        raw.group().name()
                )
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

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private record ApiKeyStats(long total_api_keys, long active_api_keys) {
    }

    private record DashboardUsageStats(
            long total_requests,
            long total_input_tokens,
            long total_output_tokens,
            long total_cache_creation_tokens,
            long total_cache_read_tokens,
            double total_cost,
            double total_actual_cost,
            long total_duration_ms,
            long today_requests,
            long today_input_tokens,
            long today_output_tokens,
            long today_cache_creation_tokens,
            long today_cache_read_tokens,
            double today_cost,
            double today_actual_cost,
            long requests_last_five_min,
            long tokens_last_five_min
    ) {
        static DashboardUsageStats empty() {
            return new DashboardUsageStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
