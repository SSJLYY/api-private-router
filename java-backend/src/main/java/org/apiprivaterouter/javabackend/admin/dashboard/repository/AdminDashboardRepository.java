package org.apiprivaterouter.javabackend.admin.dashboard.repository;

import org.apiprivaterouter.javabackend.admin.dashboard.model.AdminDashboardStatsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.BatchApiKeysUsageResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.BatchUsersUsageResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.AccountConsumptionRankingResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.GroupStatResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.ModelStatResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.RealtimeMetricsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.TrendDataPointResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.ApiKeyUsageTrendPointResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.UserBreakdownItemResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.UserSpendingRankingResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.UserUsageTrendPointResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class AdminDashboardRepository {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminDashboardRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AdminDashboardStatsResponse loadStats(long uptimeSeconds) {
        Instant now = Instant.now();
        ZonedDateTime zonedNow = ZonedDateTime.now();
        Instant todayStart = zonedNow.toLocalDate().atStartOfDay(zonedNow.getZone()).toInstant();
        Instant todayEnd = todayStart.plusSeconds(24 * 60 * 60);
        Instant hourStart = zonedNow.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toInstant();
        Instant hourEnd = hourStart.plusSeconds(60 * 60);
        Instant fiveMinutesAgo = now.minusSeconds(5 * 60);

        DashboardEntityStats entityStats = jdbcTemplate.queryForObject("""
                select
                    (select count(*) from users where deleted_at is null) as total_users,
                    (select count(*) from users where deleted_at is null and created_at >= :todayStart) as today_new_users,
                    (select count(*) from api_keys where deleted_at is null) as total_api_keys,
                    (select count(*) from api_keys where deleted_at is null and status = 'active') as active_api_keys,
                    (select count(*) from accounts where deleted_at is null) as total_accounts,
                    (select count(*) from accounts where deleted_at is null and status = 'active' and schedulable = true) as normal_accounts,
                    (select count(*) from accounts where deleted_at is null and status = 'error') as error_accounts,
                    (select count(*) from accounts where deleted_at is null and rate_limited_at is not null and rate_limit_reset_at > :nowTs) as ratelimit_accounts,
                    (select count(*) from accounts where deleted_at is null and overload_until is not null and overload_until > :nowTs) as overload_accounts
                """, new MapSqlParameterSource()
                .addValue("todayStart", Timestamp.from(todayStart))
                .addValue("nowTs", Timestamp.from(now)), (rs, rowNum) -> new DashboardEntityStats(
                rs.getLong("total_users"),
                rs.getLong("today_new_users"),
                rs.getLong("total_api_keys"),
                rs.getLong("active_api_keys"),
                rs.getLong("total_accounts"),
                rs.getLong("normal_accounts"),
                rs.getLong("error_accounts"),
                rs.getLong("ratelimit_accounts"),
                rs.getLong("overload_accounts")
        ));
        if (entityStats == null) {
            entityStats = new DashboardEntityStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        DashboardUsageStats usageStats = jdbcTemplate.queryForObject("""
                with scoped as (
                    select
                        created_at,
                        user_id,
                        input_tokens,
                        output_tokens,
                        cache_creation_tokens,
                        cache_read_tokens,
                        total_cost,
                        actual_cost,
                        coalesce(account_stats_cost, total_cost) * coalesce(account_rate_multiplier, 1) as account_cost,
                        coalesce(duration_ms, 0) as duration_ms
                    from usage_logs
                )
                select
                    count(*) as total_requests,
                    coalesce(sum(input_tokens), 0) as total_input_tokens,
                    coalesce(sum(output_tokens), 0) as total_output_tokens,
                    coalesce(sum(cache_creation_tokens), 0) as total_cache_creation_tokens,
                    coalesce(sum(cache_read_tokens), 0) as total_cache_read_tokens,
                    coalesce(sum(total_cost), 0) as total_cost,
                    coalesce(sum(actual_cost), 0) as total_actual_cost,
                    coalesce(sum(account_cost), 0) as total_account_cost,
                    coalesce(sum(duration_ms), 0) as total_duration_ms,
                    count(*) filter (where created_at >= :todayStart and created_at < :todayEnd) as today_requests,
                    coalesce(sum(input_tokens) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_input_tokens,
                    coalesce(sum(output_tokens) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_output_tokens,
                    coalesce(sum(cache_creation_tokens) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_cache_creation_tokens,
                    coalesce(sum(cache_read_tokens) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_cache_read_tokens,
                    coalesce(sum(total_cost) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_cost,
                    coalesce(sum(actual_cost) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_actual_cost,
                    coalesce(sum(account_cost) filter (where created_at >= :todayStart and created_at < :todayEnd), 0) as today_account_cost,
                    count(distinct case when created_at >= :todayStart and created_at < :todayEnd then user_id end) as active_users,
                    count(distinct case when created_at >= :hourStart and created_at < :hourEnd then user_id end) as hourly_active_users,
                    count(*) filter (where created_at >= :fiveMinutesAgo) as requests_last_five_min,
                    coalesce(sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens) filter (where created_at >= :fiveMinutesAgo), 0) as tokens_last_five_min
                from scoped
                """, new MapSqlParameterSource()
                .addValue("todayStart", Timestamp.from(todayStart))
                .addValue("todayEnd", Timestamp.from(todayEnd))
                .addValue("hourStart", Timestamp.from(hourStart))
                .addValue("hourEnd", Timestamp.from(hourEnd))
                .addValue("fiveMinutesAgo", Timestamp.from(fiveMinutesAgo)), (rs, rowNum) -> mapUsageStats(rs));
        if (usageStats == null) {
            usageStats = DashboardUsageStats.empty();
        }

        long totalTokens = usageStats.total_input_tokens + usageStats.total_output_tokens
                + usageStats.total_cache_creation_tokens + usageStats.total_cache_read_tokens;
        long todayTokens = usageStats.today_input_tokens + usageStats.today_output_tokens
                + usageStats.today_cache_creation_tokens + usageStats.today_cache_read_tokens;
        double avgDuration = usageStats.total_requests == 0 ? 0.0 : (double) usageStats.total_duration_ms / usageStats.total_requests;

        return new AdminDashboardStatsResponse(
                entityStats.total_users,
                entityStats.today_new_users,
                usageStats.active_users,
                entityStats.total_api_keys,
                entityStats.active_api_keys,
                entityStats.total_accounts,
                entityStats.normal_accounts,
                entityStats.error_accounts,
                entityStats.ratelimit_accounts,
                entityStats.overload_accounts,
                usageStats.total_requests,
                usageStats.total_input_tokens,
                usageStats.total_output_tokens,
                usageStats.total_cache_creation_tokens,
                usageStats.total_cache_read_tokens,
                totalTokens,
                usageStats.total_cost,
                usageStats.total_actual_cost,
                usageStats.total_account_cost,
                usageStats.today_requests,
                usageStats.today_input_tokens,
                usageStats.today_output_tokens,
                usageStats.today_cache_creation_tokens,
                usageStats.today_cache_read_tokens,
                todayTokens,
                usageStats.today_cost,
                usageStats.today_actual_cost,
                usageStats.today_account_cost,
                avgDuration,
                uptimeSeconds,
                usageStats.requests_last_five_min / 5,
                usageStats.tokens_last_five_min / 5,
                usageStats.hourly_active_users,
                now.toString(),
                false
        );
    }

    public UserSpendingRankingResponse loadUserSpendingRanking(LocalDate startDate, LocalDate endDate, ZoneId zoneId, int limit) {
        Instant start = startDate.atStartOfDay(zoneId).toInstant();
        Instant endExclusive = endDate.plusDays(1L).atStartOfDay(zoneId).toInstant();
        int normalizedLimit = Math.max(1, Math.min(limit, 50));
        List<UserSpendingRankingResponse.UserSpendingRankingItem> ranking = jdbcTemplate.query("""
                with user_spend as (
                    select
                        u.user_id,
                        coalesce(us.email, '') as email,
                        coalesce(sum(u.actual_cost), 0) as actual_cost,
                        count(*) as requests,
                        coalesce(sum(u.input_tokens + u.output_tokens + u.cache_creation_tokens + u.cache_read_tokens), 0) as tokens
                    from usage_logs u
                    left join users us on us.id = u.user_id
                    where u.created_at >= :startTime and u.created_at < :endTime
                    group by u.user_id, us.email
                ),
                ranked as (
                    select
                        user_id,
                        email,
                        actual_cost,
                        requests,
                        tokens,
                        coalesce(sum(actual_cost) over (), 0) as total_actual_cost,
                        coalesce(sum(requests) over (), 0) as total_requests,
                        coalesce(sum(tokens) over (), 0) as total_tokens
                    from user_spend
                    order by actual_cost desc, tokens desc, user_id asc
                    limit :limit
                )
                select user_id, email, actual_cost, requests, tokens
                from ranked
                order by actual_cost desc, tokens desc, user_id asc
                """, new MapSqlParameterSource()
                .addValue("startTime", Timestamp.from(start))
                .addValue("endTime", Timestamp.from(endExclusive))
                .addValue("limit", normalizedLimit), (rs, rowNum) -> new UserSpendingRankingResponse.UserSpendingRankingItem(
                rs.getLong("user_id"),
                rs.getString("email"),
                rs.getDouble("actual_cost"),
                rs.getLong("requests"),
                rs.getLong("tokens")
        ));

        RankingTotals totals = jdbcTemplate.queryForObject("""
                select
                    coalesce(sum(actual_cost), 0) as total_actual_cost,
                    coalesce(sum(requests), 0) as total_requests,
                    coalesce(sum(tokens), 0) as total_tokens
                from (
                    select
                        u.user_id,
                        coalesce(sum(u.actual_cost), 0) as actual_cost,
                        count(*) as requests,
                        coalesce(sum(u.input_tokens + u.output_tokens + u.cache_creation_tokens + u.cache_read_tokens), 0) as tokens
                    from usage_logs u
                    where u.created_at >= :startTime and u.created_at < :endTime
                    group by u.user_id
                ) summary
                """, new MapSqlParameterSource()
                .addValue("startTime", Timestamp.from(start))
                .addValue("endTime", Timestamp.from(endExclusive)), (rs, rowNum) -> new RankingTotals(
                rs.getDouble("total_actual_cost"),
                rs.getLong("total_requests"),
                rs.getLong("total_tokens")
        ));
        if (totals == null) {
            totals = new RankingTotals(0, 0, 0);
        }
        return new UserSpendingRankingResponse(
                ranking,
                totals.total_actual_cost,
                totals.total_requests,
                totals.total_tokens,
                startDate.format(DATE_FORMATTER),
                endDate.format(DATE_FORMATTER)
        );
    }

    public AccountConsumptionRankingResponse loadAccountConsumptionRanking(LocalDate startDate, LocalDate endDate, ZoneId zoneId, int limit) {
        Instant start = startDate.atStartOfDay(zoneId).toInstant();
        Instant endExclusive = endDate.plusDays(1L).atStartOfDay(zoneId).toInstant();
        int normalizedLimit = Math.max(1, Math.min(limit, 50));
        List<AccountConsumptionRankingResponse.AccountConsumptionRankingItem> ranking = jdbcTemplate.query("""
                with account_spend as (
                    select
                        ul.account_id,
                        coalesce(a.name, '') as account_name,
                        coalesce(a.platform, '') as platform,
                        coalesce(sum(coalesce(ul.account_stats_cost, ul.actual_cost, 0)), 0) as account_cost,
                        coalesce(sum(ul.actual_cost), 0) as actual_cost,
                        count(*) as requests,
                        coalesce(sum(ul.input_tokens + ul.output_tokens + ul.cache_creation_tokens + ul.cache_read_tokens), 0) as tokens
                    from usage_logs ul
                    left join accounts a on a.id = ul.account_id
                    where ul.created_at >= :startTime and ul.created_at < :endTime
                    group by ul.account_id, a.name, a.platform
                )
                select account_id, account_name, platform, account_cost, actual_cost, requests, tokens
                from account_spend
                order by account_cost desc, tokens desc, account_id asc
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("startTime", Timestamp.from(start))
                .addValue("endTime", Timestamp.from(endExclusive))
                .addValue("limit", normalizedLimit), (rs, rowNum) -> new AccountConsumptionRankingResponse.AccountConsumptionRankingItem(
                rs.getLong("account_id"),
                rs.getString("account_name"),
                rs.getString("platform"),
                rs.getDouble("account_cost"),
                rs.getDouble("actual_cost"),
                rs.getLong("requests"),
                rs.getLong("tokens")
        ));

        AccountRankingTotals totals = jdbcTemplate.queryForObject("""
                select
                    coalesce(sum(account_cost), 0) as total_account_cost,
                    coalesce(sum(requests), 0) as total_requests,
                    coalesce(sum(tokens), 0) as total_tokens
                from (
                    select
                        ul.account_id,
                        coalesce(sum(coalesce(ul.account_stats_cost, ul.actual_cost, 0)), 0) as account_cost,
                        count(*) as requests,
                        coalesce(sum(ul.input_tokens + ul.output_tokens + ul.cache_creation_tokens + ul.cache_read_tokens), 0) as tokens
                    from usage_logs ul
                    where ul.created_at >= :startTime and ul.created_at < :endTime
                    group by ul.account_id
                ) summary
                """, new MapSqlParameterSource()
                .addValue("startTime", Timestamp.from(start))
                .addValue("endTime", Timestamp.from(endExclusive)), (rs, rowNum) -> new AccountRankingTotals(
                rs.getDouble("total_account_cost"),
                rs.getLong("total_requests"),
                rs.getLong("total_tokens")
        ));
        if (totals == null) {
            totals = new AccountRankingTotals(0, 0, 0);
        }

        return new AccountConsumptionRankingResponse(
                ranking,
                totals.total_account_cost,
                totals.total_requests,
                totals.total_tokens,
                startDate.format(DATE_FORMATTER),
                endDate.format(DATE_FORMATTER)
        );
    }

    public BatchUsersUsageResponse loadBatchUsersUsage(List<Long> userIds, ZoneId zoneId) {
        List<Long> normalized = normalizeIds(userIds);
        if (normalized.isEmpty()) {
            return new BatchUsersUsageResponse(Map.of());
        }
        Instant todayStart = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant();
        Map<String, BatchUsersUsageResponse.BatchUserUsageStats> result = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select
                    user_id,
                    coalesce(sum(actual_cost), 0) as total_actual_cost,
                    coalesce(sum(case when created_at >= :todayStart then actual_cost else 0 end), 0) as today_actual_cost
                from usage_logs
                where user_id in (:userIds)
                group by user_id
                """, new MapSqlParameterSource()
                .addValue("todayStart", Timestamp.from(todayStart))
                .addValue("userIds", normalized), rs -> {
            while (rs.next()) {
                long userId = rs.getLong("user_id");
                result.put(String.valueOf(userId), new BatchUsersUsageResponse.BatchUserUsageStats(
                        userId,
                        rs.getDouble("today_actual_cost"),
                        rs.getDouble("total_actual_cost")
                ));
            }
        });
        for (Long userId : normalized) {
            result.putIfAbsent(String.valueOf(userId), new BatchUsersUsageResponse.BatchUserUsageStats(userId, 0, 0));
        }
        return new BatchUsersUsageResponse(result);
    }

    public BatchApiKeysUsageResponse loadBatchApiKeysUsage(List<Long> apiKeyIds, ZoneId zoneId) {
        List<Long> normalized = normalizeIds(apiKeyIds);
        if (normalized.isEmpty()) {
            return new BatchApiKeysUsageResponse(Map.of());
        }
        Instant todayStart = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant();
        Map<String, BatchApiKeysUsageResponse.BatchApiKeyUsageStats> result = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select
                    api_key_id,
                    coalesce(sum(actual_cost), 0) as total_actual_cost,
                    coalesce(sum(case when created_at >= :todayStart then actual_cost else 0 end), 0) as today_actual_cost
                from usage_logs
                where api_key_id in (:apiKeyIds)
                group by api_key_id
                """, new MapSqlParameterSource()
                .addValue("todayStart", Timestamp.from(todayStart))
                .addValue("apiKeyIds", normalized), rs -> {
            while (rs.next()) {
                long apiKeyId = rs.getLong("api_key_id");
                result.put(String.valueOf(apiKeyId), new BatchApiKeysUsageResponse.BatchApiKeyUsageStats(
                        apiKeyId,
                        rs.getDouble("today_actual_cost"),
                        rs.getDouble("total_actual_cost")
                ));
            }
        });
        for (Long apiKeyId : normalized) {
            result.putIfAbsent(String.valueOf(apiKeyId), new BatchApiKeysUsageResponse.BatchApiKeyUsageStats(apiKeyId, 0, 0));
        }
        return new BatchApiKeysUsageResponse(result);
    }

    public RealtimeMetricsResponse loadRealtimeMetrics() {
        return new RealtimeMetricsResponse(0, 0, 0, 0);
    }

    public List<TrendDataPointResponse> loadUsageTrend(
            Instant startTime,
            Instant endTime,
            String granularity,
            Long userId,
            Long apiKeyId,
            Long accountId,
            Long groupId,
            String model,
            String requestType,
            Boolean stream,
            Integer billingType
    ) {
        String dateFormat = "hour".equalsIgnoreCase(granularity) ? "YYYY-MM-DD HH24:00" : "YYYY-MM-DD";
        StringBuilder sql = new StringBuilder("""
                select
                    to_char(created_at, '%s') as date,
                    count(*) as requests,
                    coalesce(sum(input_tokens), 0) as input_tokens,
                    coalesce(sum(output_tokens), 0) as output_tokens,
                    coalesce(sum(cache_creation_tokens), 0) as cache_creation_tokens,
                    coalesce(sum(cache_read_tokens), 0) as cache_read_tokens,
                    coalesce(sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0) as total_tokens,
                    coalesce(sum(total_cost), 0) as cost,
                    coalesce(sum(actual_cost), 0) as actual_cost
                from usage_logs
                where created_at >= :startTime and created_at < :endTime
                """.formatted(dateFormat));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startTime", Timestamp.from(startTime))
                .addValue("endTime", Timestamp.from(endTime));
        if (userId != null) {
            sql.append(" and user_id = :userId");
            params.addValue("userId", userId);
        }
        if (apiKeyId != null) {
            sql.append(" and api_key_id = :apiKeyId");
            params.addValue("apiKeyId", apiKeyId);
        }
        if (accountId != null) {
            sql.append(" and account_id = :accountId");
            params.addValue("accountId", accountId);
        }
        if (groupId != null) {
            sql.append(" and group_id = :groupId");
            params.addValue("groupId", groupId);
        }
        String normalizedModel = blankToNull(model);
        if (normalizedModel != null) {
            sql.append(" and model = :model");
            params.addValue("model", normalizedModel);
        }
        if (billingType != null) {
            sql.append(" and billing_type = :billingType");
            params.addValue("billingType", billingType);
        }
        String normalizedRequestType = blankToNull(requestType);
        if (normalizedRequestType != null) {
            sql.append(" and (")
                    .append("(:requestType = 'sync' and (request_type = 1 or (request_type = 0 and stream = false and openai_ws_mode = false)))")
                    .append(" or (:requestType = 'stream' and (request_type = 2 or (request_type = 0 and stream = true and openai_ws_mode = false)))")
                    .append(" or (:requestType = 'ws_v2' and (request_type = 3 or (request_type = 0 and openai_ws_mode = true)))")
                    .append(" or (:requestType = 'unknown' and request_type = 0))");
            params.addValue("requestType", normalizedRequestType);
        } else if (stream != null) {
            sql.append(" and stream = :stream");
            params.addValue("stream", stream);
        }
        sql.append(" group by 1 order by date asc");
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new TrendDataPointResponse(
                rs.getString("date"),
                rs.getLong("requests"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_creation_tokens"),
                rs.getLong("cache_read_tokens"),
                rs.getLong("total_tokens"),
                rs.getDouble("cost"),
                rs.getDouble("actual_cost")
        ));
    }

    public List<ModelStatResponse> loadModelStats(
            Instant startTime,
            Instant endTime,
            Long userId,
            Long apiKeyId,
            Long accountId,
            Long groupId,
            String requestType,
            Boolean stream,
            Integer billingType,
            String modelSource
    ) {
        String actualCostExpr = accountId != null && userId == null && apiKeyId == null
                ? "coalesce(sum(coalesce(account_stats_cost, total_cost) * coalesce(account_rate_multiplier, 1)), 0)"
                : "coalesce(sum(actual_cost), 0)";
        String modelExpr = resolveModelExpression(modelSource);
        String sqlTemplate = """
                select
                    %s as model,
                    count(*) as requests,
                    coalesce(sum(input_tokens), 0) as input_tokens,
                    coalesce(sum(output_tokens), 0) as output_tokens,
                    coalesce(sum(cache_creation_tokens), 0) as cache_creation_tokens,
                    coalesce(sum(cache_read_tokens), 0) as cache_read_tokens,
                    coalesce(sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0) as total_tokens,
                    coalesce(sum(total_cost), 0) as cost,
                    %s as actual_cost,
                    coalesce(sum(coalesce(account_stats_cost, total_cost) * coalesce(account_rate_multiplier, 1)), 0) as account_cost
                from usage_logs
                where created_at >= :startTime and created_at < :endTime
                """;
        StringBuilder sql = new StringBuilder(sqlTemplate.formatted(modelExpr, actualCostExpr));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("startTime", Timestamp.from(startTime))
                .addValue("endTime", Timestamp.from(endTime));
        if (userId != null) {
            sql.append(" and user_id = :userId");
            params.addValue("userId", userId);
        }
        if (apiKeyId != null) {
            sql.append(" and api_key_id = :apiKeyId");
            params.addValue("apiKeyId", apiKeyId);
        }
        if (accountId != null) {
            sql.append(" and account_id = :accountId");
            params.addValue("accountId", accountId);
        }
        if (groupId != null) {
            sql.append(" and group_id = :groupId");
            params.addValue("groupId", groupId);
        }
        String normalizedRequestType = blankToNull(requestType);
        if (normalizedRequestType != null) {
            sql.append(" and (")
                    .append("(:requestType = 'sync' and (request_type = 1 or (request_type = 0 and stream = false and openai_ws_mode = false)))")
                    .append(" or (:requestType = 'stream' and (request_type = 2 or (request_type = 0 and stream = true and openai_ws_mode = false)))")
                    .append(" or (:requestType = 'ws_v2' and (request_type = 3 or (request_type = 0 and openai_ws_mode = true)))")
                    .append(" or (:requestType = 'unknown' and request_type = 0))");
            params.addValue("requestType", normalizedRequestType);
        } else if (stream != null) {
            sql.append(" and stream = :stream");
            params.addValue("stream", stream);
        }
        if (billingType != null) {
            sql.append(" and billing_type = :billingType");
            params.addValue("billingType", billingType);
        }
        sql.append(" group by ").append(modelExpr).append(" order by total_tokens desc");
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new ModelStatResponse(
                rs.getString("model"),
                rs.getLong("requests"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_creation_tokens"),
                rs.getLong("cache_read_tokens"),
                rs.getLong("total_tokens"),
                rs.getDouble("cost"),
                rs.getDouble("actual_cost"),
                rs.getDouble("account_cost")
        ));
    }

    public List<ApiKeyUsageTrendPointResponse> loadApiKeyUsageTrend(Instant startTime, Instant endTime, String granularity, int limit) {
        String dateFormat = "hour".equalsIgnoreCase(granularity) ? "YYYY-MM-DD HH24:00" : "YYYY-MM-DD";
        int normalizedLimit = Math.max(1, Math.min(limit, 50));
        String sql = """
                with top_keys as (
                    select api_key_id
                    from usage_logs
                    where created_at >= :startTime and created_at < :endTime
                    group by api_key_id
                    order by sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens) desc
                    limit :limit
                )
                select
                    to_char(u.created_at, '%s') as date,
                    u.api_key_id,
                    coalesce(k.name, '') as key_name,
                    count(*) as requests,
                    coalesce(sum(u.input_tokens + u.output_tokens + u.cache_creation_tokens + u.cache_read_tokens), 0) as tokens
                from usage_logs u
                left join api_keys k on k.id = u.api_key_id
                where u.api_key_id in (select api_key_id from top_keys)
                  and u.created_at >= :startTime and u.created_at < :endTime
                group by 1, u.api_key_id, k.name
                order by date asc, tokens desc
                """.formatted(dateFormat);
        return jdbcTemplate.query(sql, new MapSqlParameterSource()
                .addValue("startTime", Timestamp.from(startTime))
                .addValue("endTime", Timestamp.from(endTime))
                .addValue("limit", normalizedLimit), (rs, rowNum) -> new ApiKeyUsageTrendPointResponse(
                rs.getString("date"),
                rs.getLong("api_key_id"),
                rs.getString("key_name"),
                rs.getLong("requests"),
                rs.getLong("tokens")
        ));
    }

    public List<GroupStatResponse> loadGroupStats(
            Instant startTime,
            Instant endTime,
            Long userId,
            Long apiKeyId,
            Long accountId,
            Long groupId,
            String requestType,
            Boolean stream,
            Integer billingType
    ) {
        String sql = """
                select
                    coalesce(ul.group_id, 0) as group_id,
                    coalesce(g.name, '') as group_name,
                    count(*) as requests,
                    coalesce(sum(ul.input_tokens + ul.output_tokens + ul.cache_creation_tokens + ul.cache_read_tokens), 0) as total_tokens,
                    coalesce(sum(ul.total_cost), 0) as cost,
                    coalesce(sum(ul.actual_cost), 0) as actual_cost,
                    coalesce(sum(coalesce(ul.account_stats_cost, ul.total_cost) * coalesce(ul.account_rate_multiplier, 1)), 0) as account_cost
                from usage_logs ul
                left join groups g on g.id = ul.group_id
                where ul.created_at >= :startTime and ul.created_at < :endTime
                  and (:userId is null or ul.user_id = :userId)
                  and (:apiKeyId is null or ul.api_key_id = :apiKeyId)
                  and (:accountId is null or ul.account_id = :accountId)
                  and (:groupId is null or ul.group_id = :groupId)
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
                  and (:billingType is null or ul.billing_type = :billingType)
                group by ul.group_id, g.name
                order by total_tokens desc
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource()
                .addValue("startTime", Timestamp.from(startTime))
                .addValue("endTime", Timestamp.from(endTime))
                .addValue("userId", userId)
                .addValue("apiKeyId", apiKeyId)
                .addValue("accountId", accountId)
                .addValue("groupId", groupId)
                .addValue("requestType", blankToNull(requestType))
                .addValue("stream", requestType == null ? stream : null)
                .addValue("billingType", billingType), (rs, rowNum) -> new GroupStatResponse(
                rs.getLong("group_id"),
                rs.getString("group_name"),
                rs.getLong("requests"),
                rs.getLong("total_tokens"),
                rs.getDouble("cost"),
                rs.getDouble("actual_cost"),
                rs.getDouble("account_cost")
        ));
    }

    public List<UserUsageTrendPointResponse> loadUserUsageTrend(Instant startTime, Instant endTime, String granularity, int limit) {
        String dateFormat = "hour".equalsIgnoreCase(granularity) ? "YYYY-MM-DD HH24:00" : "YYYY-MM-DD";
        int normalizedLimit = Math.max(1, Math.min(limit, 50));
        String sql = """
                with top_users as (
                    select user_id
                    from usage_logs
                    where created_at >= :startTime and created_at < :endTime
                    group by user_id
                    order by sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens) desc
                    limit :limit
                )
                select
                    to_char(u.created_at, '%s') as date,
                    u.user_id,
                    coalesce(us.email, '') as email,
                    coalesce(us.username, '') as username,
                    count(*) as requests,
                    coalesce(sum(u.input_tokens + u.output_tokens + u.cache_creation_tokens + u.cache_read_tokens), 0) as tokens,
                    coalesce(sum(u.total_cost), 0) as cost,
                    coalesce(sum(u.actual_cost), 0) as actual_cost
                from usage_logs u
                left join users us on us.id = u.user_id
                where u.user_id in (select user_id from top_users)
                  and u.created_at >= :startTime and u.created_at < :endTime
                group by 1, u.user_id, us.email, us.username
                order by date asc, tokens desc
                """.formatted(dateFormat);
        return jdbcTemplate.query(sql, new MapSqlParameterSource()
                .addValue("startTime", Timestamp.from(startTime))
                .addValue("endTime", Timestamp.from(endTime))
                .addValue("limit", normalizedLimit), (rs, rowNum) -> new UserUsageTrendPointResponse(
                rs.getString("date"),
                rs.getLong("user_id"),
                rs.getString("email"),
                rs.getString("username"),
                rs.getLong("requests"),
                rs.getLong("tokens"),
                rs.getDouble("cost"),
                rs.getDouble("actual_cost")
        ));
    }

    public List<UserBreakdownItemResponse> loadUserBreakdown(
            Instant startTime,
            Instant endTime,
            Long groupId,
            String model,
            String modelSource,
            String endpoint,
            String endpointType,
            Long userId,
            Long apiKeyId,
            Long accountId,
            String requestType,
            Boolean stream,
            Integer billingType,
            int limit
    ) {
        int normalizedLimit = Math.max(1, Math.min(limit, 200));
        String modelExpr = resolveModelExpression(modelSource);
        String endpointExpr = resolveEndpointExpression(endpointType);
        String sql = """
                select
                    coalesce(ul.user_id, 0) as user_id,
                    coalesce(u.email, '') as email,
                    count(*) as requests,
                    coalesce(sum(ul.input_tokens + ul.output_tokens + ul.cache_creation_tokens + ul.cache_read_tokens), 0) as total_tokens,
                    coalesce(sum(ul.total_cost), 0) as cost,
                    coalesce(sum(ul.actual_cost), 0) as actual_cost,
                    coalesce(sum(coalesce(ul.account_stats_cost, ul.total_cost) * coalesce(ul.account_rate_multiplier, 1)), 0) as account_cost
                from usage_logs ul
                left join users u on u.id = ul.user_id
                where ul.created_at >= :startTime and ul.created_at < :endTime
                  and (:groupId is null or ul.group_id = :groupId)
                  and (:model is null or %s = :model)
                  and (:endpoint is null or %s = :endpoint)
                  and (:userId is null or ul.user_id = :userId)
                  and (:apiKeyId is null or ul.api_key_id = :apiKeyId)
                  and (:accountId is null or ul.account_id = :accountId)
                  and (:billingType is null or ul.billing_type = :billingType)
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
                group by ul.user_id, u.email
                order by actual_cost desc
                limit :limit
                """.formatted(modelExpr, endpointExpr);
        return jdbcTemplate.query(sql, new MapSqlParameterSource()
                .addValue("startTime", Timestamp.from(startTime))
                .addValue("endTime", Timestamp.from(endTime))
                .addValue("groupId", groupId)
                .addValue("model", blankToNull(model))
                .addValue("endpoint", blankToNull(endpoint))
                .addValue("userId", userId)
                .addValue("apiKeyId", apiKeyId)
                .addValue("accountId", accountId)
                .addValue("requestType", blankToNull(requestType))
                .addValue("stream", requestType == null ? stream : null)
                .addValue("billingType", billingType)
                .addValue("limit", normalizedLimit), (rs, rowNum) -> new UserBreakdownItemResponse(
                rs.getLong("user_id"),
                rs.getString("email"),
                rs.getLong("requests"),
                rs.getLong("total_tokens"),
                rs.getDouble("cost"),
                rs.getDouble("actual_cost"),
                rs.getDouble("account_cost")
        ));
    }

    private List<Long> normalizeIds(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(id -> id != null && id > 0).distinct().toList();
    }

    private String resolveModelExpression(String modelSource) {
        String requestedExpr = "coalesce(nullif(trim(requested_model), ''), model)";
        if ("upstream".equalsIgnoreCase(modelSource)) {
            return "coalesce(nullif(trim(upstream_model), ''), " + requestedExpr + ")";
        }
        if ("mapping".equalsIgnoreCase(modelSource)) {
            return "(" + requestedExpr + " || ' -> ' || coalesce(nullif(trim(upstream_model), ''), " + requestedExpr + "))";
        }
        return requestedExpr;
    }

    private String resolveEndpointExpression(String endpointType) {
        if ("upstream".equalsIgnoreCase(endpointType)) {
            return "coalesce(nullif(trim(ul.upstream_endpoint), ''), 'unknown')";
        }
        if ("path".equalsIgnoreCase(endpointType)) {
            return "concat(coalesce(nullif(trim(ul.inbound_endpoint), ''), 'unknown'), ' -> ', coalesce(nullif(trim(ul.upstream_endpoint), ''), 'unknown'))";
        }
        return "coalesce(nullif(trim(ul.inbound_endpoint), ''), 'unknown')";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private DashboardUsageStats mapUsageStats(ResultSet rs) throws SQLException {
        return new DashboardUsageStats(
                rs.getLong("total_requests"),
                rs.getLong("total_input_tokens"),
                rs.getLong("total_output_tokens"),
                rs.getLong("total_cache_creation_tokens"),
                rs.getLong("total_cache_read_tokens"),
                rs.getDouble("total_cost"),
                rs.getDouble("total_actual_cost"),
                rs.getDouble("total_account_cost"),
                rs.getLong("total_duration_ms"),
                rs.getLong("today_requests"),
                rs.getLong("today_input_tokens"),
                rs.getLong("today_output_tokens"),
                rs.getLong("today_cache_creation_tokens"),
                rs.getLong("today_cache_read_tokens"),
                rs.getDouble("today_cost"),
                rs.getDouble("today_actual_cost"),
                rs.getDouble("today_account_cost"),
                rs.getLong("active_users"),
                rs.getLong("hourly_active_users"),
                rs.getLong("requests_last_five_min"),
                rs.getLong("tokens_last_five_min")
        );
    }

    private record DashboardEntityStats(
            long total_users,
            long today_new_users,
            long total_api_keys,
            long active_api_keys,
            long total_accounts,
            long normal_accounts,
            long error_accounts,
            long ratelimit_accounts,
            long overload_accounts
    ) {
    }

    private record DashboardUsageStats(
            long total_requests,
            long total_input_tokens,
            long total_output_tokens,
            long total_cache_creation_tokens,
            long total_cache_read_tokens,
            double total_cost,
            double total_actual_cost,
            double total_account_cost,
            long total_duration_ms,
            long today_requests,
            long today_input_tokens,
            long today_output_tokens,
            long today_cache_creation_tokens,
            long today_cache_read_tokens,
            double today_cost,
            double today_actual_cost,
            double today_account_cost,
            long active_users,
            long hourly_active_users,
            long requests_last_five_min,
            long tokens_last_five_min
    ) {
        private static DashboardUsageStats empty() {
            return new DashboardUsageStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private record RankingTotals(
            double total_actual_cost,
            long total_requests,
            long total_tokens
    ) {
    }

    private record AccountRankingTotals(
            double total_account_cost,
            long total_requests,
            long total_tokens
    ) {
    }
}
