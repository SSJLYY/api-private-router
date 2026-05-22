package org.apiprivaterouter.javabackend.admin.affiliate.repository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateAdminEntry;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateInviteRecord;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateRebateRecord;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateRecordFilter;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateTransferRecord;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateUserOverview;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateUserSummary;
import org.apiprivaterouter.javabackend.common.api.PageResponse;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AdminAffiliateRepository {

    private static final String AFFILIATE_USER_OVERVIEW_SQL = """
            select ua.user_id,
                   coalesce(u.email, ''),
                   coalesce(u.username, ''),
                   ua.aff_code,
                   coalesce(ua.aff_rebate_rate_percent, 0)::double precision,
                   (ua.aff_rebate_rate_percent is not null) as has_custom_rate,
                   ua.aff_count,
                   coalesce(rebated.rebated_invitee_count, 0),
                   (ua.aff_quota + coalesce(matured.matured_frozen_quota, 0))::double precision,
                   ua.aff_history_quota::double precision
            from user_affiliates ua
            join users u on u.id = ua.user_id
            left join (
                select user_id, count(distinct source_user_id)::integer as rebated_invitee_count
                from user_affiliate_ledger
                where action = 'accrue' and source_user_id is not null
                group by user_id
            ) rebated on rebated.user_id = ua.user_id
            left join (
                select user_id, coalesce(sum(amount), 0)::double precision as matured_frozen_quota
                from user_affiliate_ledger
                where action = 'accrue' and frozen_until is not null and frozen_until <= now()
                group by user_id
            ) matured on matured.user_id = ua.user_id
            where ua.user_id = :userId
            limit 1
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminAffiliateRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResponse<AffiliateAdminEntry> listUsersWithCustomSettings(int page, int pageSize, String search) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = pageSize <= 0 || pageSize > 200 ? 20 : pageSize;
        int offset = (normalizedPage - 1) * normalizedPageSize;
        String likeSearch = "%" + (search == null ? "" : search.trim()) + "%";

        String baseFrom = """
                from user_affiliates ua
                join users u on u.id = ua.user_id
                where (ua.aff_code_custom = true or ua.aff_rebate_rate_percent is not null)
                  and (u.email ilike :likeSearch or u.username ilike :likeSearch)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("likeSearch", likeSearch)
                .addValue("limit", normalizedPageSize)
                .addValue("offset", offset);
        Long total = jdbcTemplate.queryForObject("select count(*) " + baseFrom, params, Long.class);
        List<AffiliateAdminEntry> items = jdbcTemplate.query("""
                select ua.user_id,
                       coalesce(u.email, '') as email,
                       coalesce(u.username, '') as username,
                       ua.aff_code,
                       ua.aff_code_custom,
                       ua.aff_rebate_rate_percent,
                       ua.aff_count
                """ + baseFrom + """
                order by ua.updated_at desc
                limit :limit offset :offset
                """, params, (rs, rowNum) -> new AffiliateAdminEntry(
                rs.getLong("user_id"),
                rs.getString("email"),
                rs.getString("username"),
                rs.getString("aff_code"),
                rs.getBoolean("aff_code_custom"),
                rs.getObject("aff_rebate_rate_percent", Double.class),
                rs.getInt("aff_count")
        ));
        return new PageResponse<>(items, total == null ? 0 : total, normalizedPage, normalizedPageSize);
    }

    public List<AffiliateUserSummary> lookupUsers(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                select id, email, username
                from users
                where deleted_at is null
                  and (email ilike :likeSearch or username ilike :likeSearch)
                order by email asc
                limit 20
                """, new MapSqlParameterSource("likeSearch", "%" + keyword.trim() + "%"), (rs, rowNum) ->
                new AffiliateUserSummary(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("username")
                ));
    }

    public void ensureUserAffiliate(long userId) {
        if (existsUserAffiliate(userId)) {
            return;
        }
        jdbcTemplate.update("""
                insert into user_affiliates (user_id, aff_code, created_at, updated_at)
                values (:userId, :affCode, now(), now())
                on conflict (user_id) do nothing
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("affCode", "AFF-" + userId));
    }

    public boolean existsUser(long userId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from users
                where id = :userId and deleted_at is null
                """, new MapSqlParameterSource("userId", userId), Long.class);
        return count != null && count > 0;
    }

    public void updateUserAffCode(long userId, String code) {
        try {
            int affected = jdbcTemplate.update("""
                    update user_affiliates
                    set aff_code = :code,
                        aff_code_custom = true,
                        updated_at = now()
                    where user_id = :userId
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("code", code));
            if (affected == 0) {
                throw new IllegalStateException("USER_NOT_FOUND");
            }
        } catch (DataIntegrityViolationException ex) {
            throw ex;
        }
    }

    public void resetUserAffCode(long userId, String code) {
        int affected = jdbcTemplate.update("""
                update user_affiliates
                set aff_code = :code,
                    aff_code_custom = false,
                    updated_at = now()
                where user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("code", code));
        if (affected == 0) {
            throw new IllegalStateException("USER_NOT_FOUND");
        }
    }

    public void setUserRebateRate(long userId, Double ratePercent) {
        int affected = jdbcTemplate.update("""
                update user_affiliates
                set aff_rebate_rate_percent = :ratePercent,
                    updated_at = now()
                where user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ratePercent", ratePercent));
        if (affected == 0) {
            throw new IllegalStateException("USER_NOT_FOUND");
        }
    }

    public int batchSetUserRebateRate(List<Long> userIds, Double ratePercent) {
        if (userIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.getJdbcTemplate().execute((Connection connection) -> {
            Array sqlArray = connection.createArrayOf("bigint", userIds.toArray());
            try {
                return jdbcTemplate.update("""
                        update user_affiliates
                        set aff_rebate_rate_percent = :ratePercent,
                            updated_at = now()
                        where user_id = any(:userIds)
                        """, new MapSqlParameterSource()
                        .addValue("ratePercent", ratePercent)
                        .addValue("userIds", sqlArray));
            } finally {
                try {
                    sqlArray.free();
                } catch (SQLException ignored) {
                }
            }
        });
    }

    public Optional<AffiliateUserOverviewRow> findUserOverviewRow(long userId) {
        List<AffiliateUserOverviewRow> rows = jdbcTemplate.query(AFFILIATE_USER_OVERVIEW_SQL,
                new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> new AffiliateUserOverviewRow(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getDouble(5),
                        rs.getBoolean(6),
                        rs.getInt(7),
                        rs.getInt(8),
                        rs.getDouble(9),
                        rs.getDouble(10)
                ));
        return rows.stream().findFirst();
    }

    public PageResponse<AffiliateInviteRecord> listInviteRecords(AffiliateRecordFilter filter) {
        FilterSql filterSql = buildFilterSql(filter, "ua.created_at", List.of(
                "inviter.email", "inviter.username", "invitee.email", "invitee.username",
                "ua.inviter_id::text", "ua.user_id::text", "inviter_aff.aff_code"
        ));
        String baseSql = """
                from user_affiliates ua
                join users invitee on invitee.id = ua.user_id
                join users inviter on inviter.id = ua.inviter_id
                join user_affiliates inviter_aff on inviter_aff.user_id = ua.inviter_id
                """;
        Long total = jdbcTemplate.queryForObject("select count(*) " + baseSql + filterSql.whereClause(),
                filterSql.params(), Long.class);
        String orderBy = buildOrderBy(filter, Map.of(
                "inviter", "inviter.email",
                "invitee", "invitee.email",
                "aff_code", "inviter_aff.aff_code",
                "total_rebate", "total_rebate",
                "created_at", "ua.created_at"
        ), "ua.created_at");
        MapSqlParameterSource params = filterSql.params()
                .addValue("limit", filter.page_size())
                .addValue("offset", (filter.page() - 1) * filter.page_size());
        List<AffiliateInviteRecord> items = jdbcTemplate.query("""
                select ua.inviter_id,
                       coalesce(inviter.email, '') as inviter_email,
                       coalesce(inviter.username, '') as inviter_username,
                       ua.user_id,
                       coalesce(invitee.email, '') as invitee_email,
                       coalesce(invitee.username, '') as invitee_username,
                       coalesce(inviter_aff.aff_code, '') as aff_code,
                       coalesce(sum(ual.amount), 0)::double precision as total_rebate,
                       ua.created_at
                """ + baseSql + """
                left join user_affiliate_ledger ual
                       on ual.user_id = ua.inviter_id
                      and ual.source_user_id = ua.user_id
                      and ual.action = 'accrue'
                """ + filterSql.whereClause() + """
                group by ua.inviter_id, inviter.email, inviter.username, ua.user_id, invitee.email, invitee.username, inviter_aff.aff_code, ua.created_at
                """ + orderBy + """
                limit :limit offset :offset
                """, params, (rs, rowNum) -> new AffiliateInviteRecord(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getLong(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getDouble(8),
                rs.getObject(9, OffsetDateTime.class)
        ));
        return new PageResponse<>(items, total == null ? 0 : total, filter.page(), filter.page_size());
    }

    public PageResponse<AffiliateRebateRecord> listRebateRecords(AffiliateRecordFilter filter) {
        FilterSql filterSql = buildFilterSql(filter, "ual.created_at", List.of(
                "inviter.email", "inviter.username", "invitee.email", "invitee.username",
                "po.id::text", "po.out_trade_no", "po.payment_type", "po.status"
        ));
        String whereClause = filterSql.whereClause().isBlank()
                ? ""
                : filterSql.whereClause().replaceFirst("where", "and");
        String baseSql = """
                from user_affiliate_ledger ual
                join payment_orders po on po.id = ual.source_order_id
                join users invitee on invitee.id = ual.source_user_id
                join users inviter on inviter.id = ual.user_id
                where ual.action = 'accrue'
                  and ual.source_order_id is not null
                """ + whereClause;
        Long total = jdbcTemplate.queryForObject("select count(*) " + baseSql, filterSql.params(), Long.class);
        String orderBy = buildOrderBy(filter, Map.of(
                "order", "po.id",
                "inviter", "inviter.email",
                "invitee", "invitee.email",
                "order_amount", "po.amount",
                "pay_amount", "po.pay_amount",
                "rebate_amount", "ual.amount",
                "payment_type", "po.payment_type",
                "order_status", "po.status",
                "created_at", "ual.created_at"
        ), "ual.created_at");
        MapSqlParameterSource params = filterSql.params()
                .addValue("limit", filter.page_size())
                .addValue("offset", (filter.page() - 1) * filter.page_size());
        List<AffiliateRebateRecord> items = jdbcTemplate.query("""
                select po.id,
                       po.out_trade_no,
                       ual.user_id,
                       coalesce(inviter.email, '') as inviter_email,
                       coalesce(inviter.username, '') as inviter_username,
                       ual.source_user_id,
                       coalesce(invitee.email, '') as invitee_email,
                       coalesce(invitee.username, '') as invitee_username,
                       po.amount::double precision,
                       po.pay_amount::double precision,
                       ual.amount::double precision,
                       po.payment_type,
                       po.status,
                       ual.created_at
                """ + baseSql + "\n" + orderBy + """
                limit :limit offset :offset
                """, params, (rs, rowNum) -> new AffiliateRebateRecord(
                rs.getLong(1),
                rs.getString(2),
                rs.getLong(3),
                rs.getString(4),
                rs.getString(5),
                rs.getLong(6),
                rs.getString(7),
                rs.getString(8),
                rs.getDouble(9),
                rs.getDouble(10),
                rs.getDouble(11),
                rs.getString(12),
                rs.getString(13),
                rs.getObject(14, OffsetDateTime.class)
        ));
        return new PageResponse<>(items, total == null ? 0 : total, filter.page(), filter.page_size());
    }

    public PageResponse<AffiliateTransferRecord> listTransferRecords(AffiliateRecordFilter filter) {
        FilterSql filterSql = buildFilterSql(filter, "ual.created_at", List.of(
                "u.email", "u.username", "u.id::text"
        ));
        String whereClause = filterSql.whereClause().isBlank()
                ? ""
                : filterSql.whereClause().replaceFirst("where", "and");
        String baseSql = """
                from user_affiliate_ledger ual
                join users u on u.id = ual.user_id
                where ual.action = 'transfer'
                """ + whereClause;
        Long total = jdbcTemplate.queryForObject("select count(*) " + baseSql, filterSql.params(), Long.class);
        String orderBy = buildOrderBy(filter, Map.of(
                "user", "u.email",
                "amount", "ual.amount",
                "balance_after", "ual.balance_after",
                "available_quota_after", "ual.aff_quota_after",
                "frozen_quota_after", "ual.aff_frozen_quota_after",
                "history_quota_after", "ual.aff_history_quota_after",
                "created_at", "ual.created_at"
        ), "ual.created_at");
        MapSqlParameterSource params = filterSql.params()
                .addValue("limit", filter.page_size())
                .addValue("offset", (filter.page() - 1) * filter.page_size());
        List<AffiliateTransferRecord> items = jdbcTemplate.query("""
                select ual.id,
                       ual.user_id,
                       coalesce(u.email, '') as user_email,
                       coalesce(u.username, '') as username,
                       ual.amount::double precision,
                       ual.balance_after::double precision,
                       ual.aff_quota_after::double precision,
                       ual.aff_frozen_quota_after::double precision,
                       ual.aff_history_quota_after::double precision,
                       ual.created_at
                """ + baseSql + "\n" + orderBy + """
                limit :limit offset :offset
                """, params, (rs, rowNum) -> {
            Double balanceAfter = rs.getObject(6, Double.class);
            Double availableAfter = rs.getObject(7, Double.class);
            Double frozenAfter = rs.getObject(8, Double.class);
            Double historyAfter = rs.getObject(9, Double.class);
            boolean snapshotAvailable = balanceAfter != null
                    && availableAfter != null
                    && frozenAfter != null
                    && historyAfter != null;
            return new AffiliateTransferRecord(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getDouble(5),
                    balanceAfter,
                    availableAfter,
                    frozenAfter,
                    historyAfter,
                    snapshotAvailable,
                    rs.getObject(10, OffsetDateTime.class)
            );
        });
        return new PageResponse<>(items, total == null ? 0 : total, filter.page(), filter.page_size());
    }

    private boolean existsUserAffiliate(long userId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from user_affiliates
                where user_id = :userId
                """, new MapSqlParameterSource("userId", userId), Long.class);
        return count != null && count > 0;
    }

    private FilterSql buildFilterSql(AffiliateRecordFilter filter, String timeColumn, List<String> searchColumns) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (filter.start_at() != null) {
            clauses.add(timeColumn + " >= :startAt");
            params.addValue("startAt", filter.start_at());
        }
        if (filter.end_at() != null) {
            clauses.add(timeColumn + " <= :endAt");
            params.addValue("endAt", filter.end_at());
        }
        if (filter.search() != null && !filter.search().isBlank()) {
            params.addValue("search", "%" + filter.search().trim().toLowerCase() + "%");
            List<String> parts = new ArrayList<>();
            for (String column : searchColumns) {
                parts.add("lower(" + column + ") like :search");
            }
            clauses.add("(" + String.join(" or ", parts) + ")");
        }
        if (clauses.isEmpty()) {
            return new FilterSql("", params);
        }
        return new FilterSql(" where " + String.join(" and ", clauses), params);
    }

    private String buildOrderBy(AffiliateRecordFilter filter, Map<String, String> sortColumns, String fallbackColumn) {
        String column = sortColumns.getOrDefault(filter.sort_by(), fallbackColumn);
        String direction = filter.sort_desc() ? "desc" : "asc";
        return "order by " + column + " " + direction + " nulls last";
    }

    public record AffiliateUserOverviewRow(
            long userId,
            String email,
            String username,
            String affCode,
            double customRate,
            boolean hasCustomRate,
            int invitedCount,
            int rebatedInviteeCount,
            double availableQuota,
            double historyQuota
    ) {
        public AffiliateUserOverview toOverview(double effectiveRate) {
            return new AffiliateUserOverview(
                    userId,
                    email,
                    username,
                    affCode,
                    effectiveRate,
                    invitedCount,
                    rebatedInviteeCount,
                    availableQuota,
                    historyQuota
            );
        }
    }

    private record FilterSql(String whereClause, MapSqlParameterSource params) {
    }
}
