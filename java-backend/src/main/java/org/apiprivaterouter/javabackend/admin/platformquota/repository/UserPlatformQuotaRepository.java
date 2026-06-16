package org.apiprivaterouter.javabackend.admin.platformquota.repository;

import org.apiprivaterouter.javabackend.admin.platformquota.model.*;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class UserPlatformQuotaRepository {

    private static final Logger log = LoggerFactory.getLogger(UserPlatformQuotaRepository.class);

    private static final List<String> VALID_PLATFORMS = List.of("anthropic", "openai", "gemini", "antigravity");
    private static final List<String> VALID_WINDOWS = List.of("daily", "weekly", "monthly");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public UserPlatformQuotaRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public List<UserPlatformQuotaResponse> listByUserId(long userId) {
        String sql = """
                select id, user_id, platform,
                       daily_limit_usd, weekly_limit_usd, monthly_limit_usd,
                       daily_usage_usd, weekly_usage_usd, monthly_usage_usd,
                       daily_window_start, weekly_window_start, monthly_window_start,
                       created_at, updated_at
                from user_platform_quotas
                where user_id = :userId and deleted_at is null
                order by platform asc
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> mapQuota(rs));
    }

    public Optional<UserPlatformQuotaResponse> findByUserAndPlatform(long userId, String platform) {
        String sql = """
                select id, user_id, platform,
                       daily_limit_usd, weekly_limit_usd, monthly_limit_usd,
                       daily_usage_usd, weekly_usage_usd, monthly_usage_usd,
                       daily_window_start, weekly_window_start, monthly_window_start,
                       created_at, updated_at
                from user_platform_quotas
                where user_id = :userId and platform = :platform and deleted_at is null
                """;
        List<UserPlatformQuotaResponse> rows = jdbcTemplate.query(sql,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("platform", platform),
                (rs, rowNum) -> mapQuota(rs));
        return rows.stream().findFirst();
    }

    @Transactional
    public UserPlatformQuotaResponse upsertForUser(long userId, List<PlatformQuotaEntry> entries) {
        List<String> requestedPlatforms = entries.stream()
                .map(PlatformQuotaEntry::platform)
                .filter(VALID_PLATFORMS::contains)
                .toList();

        List<String> existingPlatforms = jdbcTemplate.query("""
                select platform from user_platform_quotas
                where user_id = :userId and deleted_at is null
                """, new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> rs.getString("platform"));

        for (String existingPlatform : existingPlatforms) {
            if (!requestedPlatforms.contains(existingPlatform)) {
                jdbcTemplate.update("""
                        update user_platform_quotas
                        set deleted_at = now(), updated_at = now()
                        where user_id = :userId and platform = :platform and deleted_at is null
                        """, new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("platform", existingPlatform));
            }
        }

        for (PlatformQuotaEntry entry : entries) {
            if (!VALID_PLATFORMS.contains(entry.platform())) {
                continue;
            }
            int updated = jdbcTemplate.update("""
                    update user_platform_quotas
                    set daily_limit_usd = :dailyLimit,
                        weekly_limit_usd = :weeklyLimit,
                        monthly_limit_usd = :monthlyLimit,
                        updated_at = now()
                    where user_id = :userId and platform = :platform and deleted_at is null
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("platform", entry.platform())
                    .addValue("dailyLimit", entry.daily_limit_usd())
                    .addValue("weeklyLimit", entry.weekly_limit_usd())
                    .addValue("monthlyLimit", entry.monthly_limit_usd()));

            if (updated == 0) {
                jdbcTemplate.update("""
                        insert into user_platform_quotas (
                            user_id, platform,
                            daily_limit_usd, weekly_limit_usd, monthly_limit_usd,
                            daily_usage_usd, weekly_usage_usd, monthly_usage_usd,
                            created_at, updated_at
                        ) values (
                            :userId, :platform,
                            :dailyLimit, :weeklyLimit, :monthlyLimit,
                            0, 0, 0,
                            now(), now()
                        )
                        """, new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("platform", entry.platform())
                        .addValue("dailyLimit", entry.daily_limit_usd())
                        .addValue("weeklyLimit", entry.weekly_limit_usd())
                        .addValue("monthlyLimit", entry.monthly_limit_usd()));
            }
        }

        return findByUserAndPlatform(userId, entries.get(0).platform()).orElse(null);
    }

    @Transactional
    public ResetPlatformQuotaResponse resetWindow(long userId, String platform, String window) {
        if (!VALID_PLATFORMS.contains(platform) || !VALID_WINDOWS.contains(window)) {
            throw new IllegalArgumentException("invalid platform or window");
        }
        String usageColumn = window + "_usage_usd";
        String windowStartColumn = window + "_window_start";
        Instant windowStart = calculateWindowStart(window);
        String sql = "update user_platform_quotas set " +
                usageColumn + " = 0, " +
                windowStartColumn + " = :windowStart, " +
                "updated_at = now() " +
                "where user_id = :userId and platform = :platform and deleted_at is null";
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("platform", platform)
                .addValue("windowStart", windowStart != null ? Timestamp.from(windowStart) : null));
        return new ResetPlatformQuotaResponse(platform, window, BigDecimal.ZERO);
    }

    @Transactional
    public BigDecimal incrementUsageWithReset(long userId, String platform, BigDecimal cost, String window) {
        if (!VALID_PLATFORMS.contains(platform) || !VALID_WINDOWS.contains(window)) {
            return BigDecimal.ZERO;
        }
        String usageColumn = window + "_usage_usd";
        String windowStartColumn = window + "_window_start";
        Instant expectedStart = calculateWindowStart(window);

        String selectSql = "select " + usageColumn + " as usage, " + windowStartColumn + " as window_start " +
                "from user_platform_quotas " +
                "where user_id = :userId and platform = :platform and deleted_at is null " +
                "for update";
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("platform", platform));

        if (rows.isEmpty()) {
            return cost;
        }

        java.util.Map<String, Object> row = rows.get(0);
        BigDecimal currentUsage = row.get("usage") instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
        Timestamp windowStartTs = (Timestamp) row.get("window_start");
        boolean needsReset = windowStartTs == null || expectedStart == null ||
                windowStartTs.toInstant().isBefore(expectedStart);

        BigDecimal newUsage = needsReset ? cost : currentUsage.add(cost);
        String updateSql = "update user_platform_quotas set " +
                usageColumn + " = :newUsage, " +
                windowStartColumn + " = coalesce(" + windowStartColumn + ", :windowStart), " +
                "updated_at = now() " +
                "where user_id = :userId and platform = :platform and deleted_at is null";
        jdbcTemplate.update(updateSql, new MapSqlParameterSource()
                .addValue("newUsage", newUsage)
                .addValue("windowStart", expectedStart != null ? Timestamp.from(expectedStart) : null)
                .addValue("userId", userId)
                .addValue("platform", platform));

        return newUsage;
    }

    public void bulkInsertInitial(long userId, List<PlatformQuotaEntry> entries) {
        for (PlatformQuotaEntry entry : entries) {
            if (!VALID_PLATFORMS.contains(entry.platform())) {
                continue;
            }
            try {
                jdbcTemplate.update("""
                        insert into user_platform_quotas (
                            user_id, platform,
                            daily_limit_usd, weekly_limit_usd, monthly_limit_usd,
                            daily_usage_usd, weekly_usage_usd, monthly_usage_usd,
                            created_at, updated_at
                        ) values (
                            :userId, :platform,
                            :dailyLimit, :weeklyLimit, :monthlyLimit,
                            0, 0, 0,
                            now(), now()
                        )
                        ON CONFLICT (user_id, platform) WHERE deleted_at IS NULL
                        DO UPDATE SET
                            daily_limit_usd = COALESCE(user_platform_quotas.daily_limit_usd, EXCLUDED.daily_limit_usd),
                            weekly_limit_usd = COALESCE(user_platform_quotas.weekly_limit_usd, EXCLUDED.weekly_limit_usd),
                            monthly_limit_usd = COALESCE(user_platform_quotas.monthly_limit_usd, EXCLUDED.monthly_limit_usd),
                            updated_at = now()
                        """, new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("platform", entry.platform())
                        .addValue("dailyLimit", entry.daily_limit_usd())
                        .addValue("weeklyLimit", entry.weekly_limit_usd())
                        .addValue("monthlyLimit", entry.monthly_limit_usd()));
            } catch (Exception ex) {
                log.warn("bulkInsertInitial failed for userId={} platform={}: {}", userId, entry.platform(), ex.getMessage());
            }
        }
    }

    private Instant calculateWindowStart(String window) {
        return switch (window) {
            case "daily" -> LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant();
            case "weekly" -> LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay(ZoneOffset.UTC).toInstant();
            case "monthly" -> LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            default -> null;
        };
    }

    private UserPlatformQuotaResponse mapQuota(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserPlatformQuotaResponse(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("platform"),
                rs.getBigDecimal("daily_limit_usd"),
                rs.getBigDecimal("weekly_limit_usd"),
                rs.getBigDecimal("monthly_limit_usd"),
                rs.getBigDecimal("daily_usage_usd"),
                rs.getBigDecimal("weekly_usage_usd"),
                rs.getBigDecimal("monthly_usage_usd"),
                toIsoString(rs.getTimestamp("daily_window_start")),
                toIsoString(rs.getTimestamp("weekly_window_start")),
                toIsoString(rs.getTimestamp("monthly_window_start")),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }
}
