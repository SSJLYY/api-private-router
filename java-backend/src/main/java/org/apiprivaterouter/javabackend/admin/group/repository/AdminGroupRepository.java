package org.apiprivaterouter.javabackend.admin.group.repository;

import org.apiprivaterouter.javabackend.admin.group.model.AdminGroupResponse;
import org.apiprivaterouter.javabackend.admin.group.model.GroupCapacitySummaryResponse;
import org.apiprivaterouter.javabackend.admin.group.model.GroupRateMultiplierEntryResponse;
import org.apiprivaterouter.javabackend.admin.group.model.GroupStatsResponse;
import org.apiprivaterouter.javabackend.admin.group.model.GroupUsageSummaryResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AdminGroupRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AdminGroupRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public PageResponse<AdminGroupResponse> listGroups(
            int page,
            int pageSize,
            String platform,
            String status,
            String search,
            Boolean isExclusive,
            String sortBy,
            String sortOrder
    ) {
        int offset = Math.max(page - 1, 0) * pageSize;
        String normalizedPlatform = blankToNull(platform);
        String normalizedStatus = blankToNull(status);
        String normalizedSearch = blankToNull(search);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        StringBuilder where = new StringBuilder("""
                where g.deleted_at is null
                """);
        if (normalizedPlatform != null) {
            where.append("\n  and g.platform = :platform");
            params.addValue("platform", normalizedPlatform);
        }
        if (normalizedStatus != null) {
            where.append("\n  and g.status = :status");
            params.addValue("status", normalizedStatus);
        }
        if (isExclusive != null) {
            where.append("\n  and g.is_exclusive = :isExclusive");
            params.addValue("isExclusive", isExclusive);
        }
        if (normalizedSearch != null) {
            where.append("\n  and (g.name ilike :likeSearch or coalesce(g.description, '') ilike :likeSearch)");
            params.addValue("likeSearch", "%" + normalizedSearch + "%");
        }
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from groups g
                """ + where, params, Long.class);
        List<AdminGroupResponse> items = jdbcTemplate.query("""
                select g.id, g.name, g.description, g.platform, g.rate_multiplier, g.rpm_limit,
                       g.is_exclusive, g.status, g.subscription_type,
                       g.daily_limit_usd, g.weekly_limit_usd, g.monthly_limit_usd,
                       g.allow_image_generation, g.image_rate_independent, g.image_rate_multiplier,
                       g.image_price_1k, g.image_price_2k, g.image_price_4k,
                       g.claude_code_only, g.fallback_group_id, g.fallback_group_id_on_invalid_request,
                       g.model_routing, g.model_routing_enabled, g.mcp_xml_inject, g.supported_model_scopes,
                       g.allow_messages_dispatch, g.require_oauth_only, g.require_privacy_set,
                       g.default_mapped_model, g.messages_dispatch_model_config,
                       g.sort_order, g.created_at, g.updated_at,
                       coalesce(aggr.account_count, 0) as account_count,
                       coalesce(aggr.active_account_count, 0) as active_account_count,
                       coalesce(aggr.rate_limited_account_count, 0) as rate_limited_account_count
                from groups g
                left join (
                    select ag.group_id,
                           count(*) as account_count,
                           count(*) filter (where a.status = 'active' and a.schedulable = true) as active_account_count,
                           count(*) filter (
                               where a.status = 'active'
                                 and (
                                     a.rate_limit_reset_at > now()
                                     or a.overload_until > now()
                                     or a.temp_unschedulable_until > now()
                                 )
                           ) as rate_limited_account_count
                    from account_groups ag
                    join accounts a on a.id = ag.account_id
                    group by ag.group_id
                ) aggr on aggr.group_id = g.id
                """ + where + buildOrderBy(sortBy, sortOrder) + """
                limit :pageSize offset :offset
                """, params, (rs, rowNum) -> mapGroup(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public List<AdminGroupResponse> listAllActiveGroups(String platform) {
        String normalizedPlatform = blankToNull(platform);
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
                select g.id, g.name, g.description, g.platform, g.rate_multiplier, g.rpm_limit,
                       g.is_exclusive, g.status, g.subscription_type,
                       g.daily_limit_usd, g.weekly_limit_usd, g.monthly_limit_usd,
                       g.allow_image_generation, g.image_rate_independent, g.image_rate_multiplier,
                       g.image_price_1k, g.image_price_2k, g.image_price_4k,
                       g.claude_code_only, g.fallback_group_id, g.fallback_group_id_on_invalid_request,
                       g.model_routing, g.model_routing_enabled, g.mcp_xml_inject, g.supported_model_scopes,
                       g.allow_messages_dispatch, g.require_oauth_only, g.require_privacy_set,
                       g.default_mapped_model, g.messages_dispatch_model_config,
                       g.sort_order, g.created_at, g.updated_at,
                       coalesce(aggr.account_count, 0) as account_count,
                       coalesce(aggr.active_account_count, 0) as active_account_count,
                       coalesce(aggr.rate_limited_account_count, 0) as rate_limited_account_count
                from groups g
                left join (
                    select ag.group_id,
                           count(*) as account_count,
                           count(*) filter (where a.status = 'active' and a.schedulable = true) as active_account_count,
                           count(*) filter (
                               where a.status = 'active'
                                 and (
                                     a.rate_limit_reset_at > now()
                                     or a.overload_until > now()
                                     or a.temp_unschedulable_until > now()
                                 )
                           ) as rate_limited_account_count
                    from account_groups ag
                    join accounts a on a.id = ag.account_id
                    group by ag.group_id
                ) aggr on aggr.group_id = g.id
                where g.deleted_at is null
                  and g.status = 'active'
                order by g.sort_order asc, g.id asc
                """);
        if (normalizedPlatform != null) {
            int orderByIndex = sql.indexOf("order by g.sort_order asc, g.id asc");
            sql.insert(orderByIndex, "  and g.platform = :platform\n");
            params.addValue("platform", normalizedPlatform);
        }
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> mapGroup(rs));
    }

    public Optional<AdminGroupResponse> getGroup(long id) {
        List<AdminGroupResponse> rows = jdbcTemplate.query("""
                select g.id, g.name, g.description, g.platform, g.rate_multiplier, g.rpm_limit,
                       g.is_exclusive, g.status, g.subscription_type,
                       g.daily_limit_usd, g.weekly_limit_usd, g.monthly_limit_usd,
                       g.allow_image_generation, g.image_rate_independent, g.image_rate_multiplier,
                       g.image_price_1k, g.image_price_2k, g.image_price_4k,
                       g.claude_code_only, g.fallback_group_id, g.fallback_group_id_on_invalid_request,
                       g.model_routing, g.model_routing_enabled, g.mcp_xml_inject, g.supported_model_scopes,
                       g.allow_messages_dispatch, g.require_oauth_only, g.require_privacy_set,
                       g.default_mapped_model, g.messages_dispatch_model_config,
                       g.sort_order, g.created_at, g.updated_at,
                       coalesce(aggr.account_count, 0) as account_count,
                       coalesce(aggr.active_account_count, 0) as active_account_count,
                       coalesce(aggr.rate_limited_account_count, 0) as rate_limited_account_count
                from groups g
                left join (
                    select ag.group_id,
                           count(*) as account_count,
                           count(*) filter (where a.status = 'active' and a.schedulable = true) as active_account_count,
                           count(*) filter (
                               where a.status = 'active'
                                 and (
                                     a.rate_limit_reset_at > now()
                                     or a.overload_until > now()
                                     or a.temp_unschedulable_until > now()
                                 )
                           ) as rate_limited_account_count
                    from account_groups ag
                    join accounts a on a.id = ag.account_id
                    group by ag.group_id
                ) aggr on aggr.group_id = g.id
                where g.id = :id and g.deleted_at is null
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapGroup(rs));
        return rows.stream().findFirst();
    }

    public Optional<GroupSnapshot> getGroupSnapshot(long id) {
        List<GroupSnapshot> rows = jdbcTemplate.query("""
                select id, name, platform, status, subscription_type,
                       claude_code_only, fallback_group_id, fallback_group_id_on_invalid_request
                from groups
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id), (rs, rowNum) -> new GroupSnapshot(
                rs.getLong("id"),
                rs.getString("name"),
                defaultString(rs.getString("platform")),
                defaultString(rs.getString("status")),
                defaultString(rs.getString("subscription_type")),
                rs.getBoolean("claude_code_only"),
                rs.getObject("fallback_group_id", Long.class),
                rs.getObject("fallback_group_id_on_invalid_request", Long.class)
        ));
        return rows.stream().findFirst();
    }

    public boolean groupExists(long id) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1 from groups where id = :id and deleted_at is null
                )
                """, new MapSqlParameterSource("id", id), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public long createGroup(GroupWriteModel model) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into groups (
                    name, description, platform, rate_multiplier, is_exclusive, status, subscription_type,
                    daily_limit_usd, weekly_limit_usd, monthly_limit_usd, default_validity_days,
                    allow_image_generation, image_rate_independent, image_rate_multiplier,
                    image_price_1k, image_price_2k, image_price_4k,
                    claude_code_only, fallback_group_id, fallback_group_id_on_invalid_request,
                    model_routing, model_routing_enabled, mcp_xml_inject, supported_model_scopes,
                    allow_messages_dispatch, require_oauth_only, require_privacy_set,
                    default_mapped_model, messages_dispatch_model_config, rpm_limit,
                    sort_order, created_at, updated_at
                ) values (
                    :name, :description, :platform, :rateMultiplier, :isExclusive, :status, :subscriptionType,
                    :dailyLimitUsd, :weeklyLimitUsd, :monthlyLimitUsd, 30,
                    :allowImageGeneration, :imageRateIndependent, :imageRateMultiplier,
                    :imagePrice1k, :imagePrice2k, :imagePrice4k,
                    :claudeCodeOnly, :fallbackGroupId, :fallbackGroupIdOnInvalidRequest,
                    cast(:modelRouting as jsonb), :modelRoutingEnabled, :mcpXmlInject, cast(:supportedModelScopes as jsonb),
                    :allowMessagesDispatch, :requireOauthOnly, :requirePrivacySet,
                    :defaultMappedModel, cast(:messagesDispatchModelConfig as jsonb), :rpmLimit,
                    :sortOrder, now(), now()
                )
                returning id
                """, groupWriteParams(model), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to create group");
        }
        return key.longValue();
    }

    public void updateGroup(long id, GroupWriteModel model) {
        int updated = jdbcTemplate.update("""
                update groups
                set name = :name,
                    description = :description,
                    platform = :platform,
                    rate_multiplier = :rateMultiplier,
                    is_exclusive = :isExclusive,
                    status = :status,
                    subscription_type = :subscriptionType,
                    daily_limit_usd = :dailyLimitUsd,
                    weekly_limit_usd = :weeklyLimitUsd,
                    monthly_limit_usd = :monthlyLimitUsd,
                    allow_image_generation = :allowImageGeneration,
                    image_rate_independent = :imageRateIndependent,
                    image_rate_multiplier = :imageRateMultiplier,
                    image_price_1k = :imagePrice1k,
                    image_price_2k = :imagePrice2k,
                    image_price_4k = :imagePrice4k,
                    claude_code_only = :claudeCodeOnly,
                    fallback_group_id = :fallbackGroupId,
                    fallback_group_id_on_invalid_request = :fallbackGroupIdOnInvalidRequest,
                    model_routing = cast(:modelRouting as jsonb),
                    model_routing_enabled = :modelRoutingEnabled,
                    mcp_xml_inject = :mcpXmlInject,
                    supported_model_scopes = cast(:supportedModelScopes as jsonb),
                    allow_messages_dispatch = :allowMessagesDispatch,
                    require_oauth_only = :requireOauthOnly,
                    require_privacy_set = :requirePrivacySet,
                    default_mapped_model = :defaultMappedModel,
                    messages_dispatch_model_config = cast(:messagesDispatchModelConfig as jsonb),
                    rpm_limit = :rpmLimit,
                    updated_at = now()
                where id = :id and deleted_at is null
                """, groupWriteParams(model).addValue("id", id));
        if (updated == 0) {
            throw new IllegalArgumentException("group not found");
        }
    }

    public void deleteGroup(long id) {
        jdbcTemplate.update("""
                update user_subscriptions
                set deleted_at = now(), updated_at = now()
                where group_id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
        jdbcTemplate.update("""
                update api_keys
                set group_id = null, updated_at = now()
                where group_id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
        jdbcTemplate.update("""
                delete from user_allowed_groups
                where group_id = :id
                """, new MapSqlParameterSource("id", id));
        jdbcTemplate.update("""
                delete from account_groups
                where group_id = :id
                """, new MapSqlParameterSource("id", id));
        int updated = jdbcTemplate.update("""
                update groups
                set deleted_at = now(), updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
        if (updated == 0) {
            throw new IllegalArgumentException("group not found");
        }
    }

    public GroupStatsResponse getGroupStats(long groupId) {
        if (!groupExists(groupId)) {
            throw new IllegalArgumentException("group not found");
        }
        return jdbcTemplate.queryForObject("""
                select
                    (select count(*) from api_keys k where k.group_id = :groupId and k.deleted_at is null) as total_api_keys,
                    (select count(*) from api_keys k where k.group_id = :groupId and k.deleted_at is null and k.status = 'active') as active_api_keys,
                    coalesce((select count(*) from usage_logs ul where ul.group_id = :groupId), 0) as total_requests,
                    coalesce((select sum(ul.actual_cost) from usage_logs ul where ul.group_id = :groupId), 0) as total_cost
                """, new MapSqlParameterSource("groupId", groupId), (rs, rowNum) -> new GroupStatsResponse(
                rs.getLong("total_api_keys"),
                rs.getLong("active_api_keys"),
                rs.getLong("total_requests"),
                rs.getDouble("total_cost")
        ));
    }

    public PageResponse<Map<String, Object>> getGroupApiKeys(long groupId, int page, int pageSize) {
        if (!groupExists(groupId)) {
            throw new IllegalArgumentException("group not found");
        }
        int offset = Math.max(page - 1, 0) * pageSize;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("groupId", groupId)
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from api_keys
                where group_id = :groupId and deleted_at is null
                """, params, Long.class);
        List<Map<String, Object>> items = jdbcTemplate.query("""
                select id, user_id, name, key, status, created_at, updated_at, expires_at, quota, quota_used
                from api_keys
                where group_id = :groupId and deleted_at is null
                order by created_at desc, id desc
                limit :pageSize offset :offset
                """, params, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("user_id", rs.getLong("user_id"));
            row.put("name", defaultString(rs.getString("name")));
            row.put("prefix", maskKeyPrefix(rs.getString("key")));
            row.put("status", defaultString(rs.getString("status")));
            row.put("quota", rs.getDouble("quota"));
            row.put("quota_used", rs.getDouble("quota_used"));
            row.put("expires_at", toIsoString(rs.getTimestamp("expires_at")));
            row.put("created_at", toIsoString(rs.getTimestamp("created_at")));
            row.put("updated_at", toIsoString(rs.getTimestamp("updated_at")));
            return row;
        });
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public List<GroupRateMultiplierEntryResponse> getGroupRateMultipliers(long groupId) {
        if (!groupExists(groupId)) {
            throw new IllegalArgumentException("group not found");
        }
        return jdbcTemplate.query("""
                select ugr.user_id,
                       coalesce(u.username, '') as user_name,
                       coalesce(u.email, '') as user_email,
                       coalesce(u.notes, '') as user_notes,
                       coalesce(u.status, '') as user_status,
                       ugr.rate_multiplier,
                       ugr.rpm_override
                from user_group_rate_multipliers ugr
                join users u on u.id = ugr.user_id and u.deleted_at is null
                where ugr.group_id = :groupId
                order by ugr.user_id asc
                """, new MapSqlParameterSource("groupId", groupId), (rs, rowNum) -> new GroupRateMultiplierEntryResponse(
                rs.getLong("user_id"),
                defaultString(rs.getString("user_name")),
                defaultString(rs.getString("user_email")),
                defaultString(rs.getString("user_notes")),
                defaultString(rs.getString("user_status")),
                rs.getObject("rate_multiplier", Double.class),
                rs.getObject("rpm_override", Integer.class)
        ));
    }

    public void clearGroupRateMultipliers(long groupId) {
        if (!groupExists(groupId)) {
            throw new IllegalArgumentException("group not found");
        }
        jdbcTemplate.update("""
                update user_group_rate_multipliers
                set rate_multiplier = null, updated_at = now()
                where group_id = :groupId
                """, new MapSqlParameterSource("groupId", groupId));
        deleteEmptyRateRows(groupId);
    }

    public void batchSetGroupRateMultipliers(long groupId, List<GroupRateWriteEntry> entries) {
        if (!groupExists(groupId)) {
            throw new IllegalArgumentException("group not found");
        }
        List<Long> keepUserIds = entries.stream().map(GroupRateWriteEntry::userId).distinct().toList();
        if (keepUserIds.isEmpty()) {
            clearGroupRateMultipliers(groupId);
            return;
        }
        validateUsersExist(keepUserIds);
        jdbcTemplate.update("""
                update user_group_rate_multipliers
                set rate_multiplier = null, updated_at = now()
                where group_id = :groupId and user_id not in (:keepUserIds)
                """, new MapSqlParameterSource()
                .addValue("groupId", groupId)
                .addValue("keepUserIds", keepUserIds));
        deleteEmptyRateRows(groupId);
        for (GroupRateWriteEntry entry : entries) {
            jdbcTemplate.update("""
                    insert into user_group_rate_multipliers (user_id, group_id, rate_multiplier, created_at, updated_at)
                    values (:userId, :groupId, :rateMultiplier, now(), now())
                    on conflict (user_id, group_id)
                    do update set rate_multiplier = excluded.rate_multiplier, updated_at = excluded.updated_at
                    """, new MapSqlParameterSource()
                    .addValue("userId", entry.userId())
                    .addValue("groupId", groupId)
                    .addValue("rateMultiplier", entry.rateMultiplier()));
        }
    }

    public void clearGroupRpmOverrides(long groupId) {
        if (!groupExists(groupId)) {
            throw new IllegalArgumentException("group not found");
        }
        jdbcTemplate.update("""
                update user_group_rate_multipliers
                set rpm_override = null, updated_at = now()
                where group_id = :groupId
                """, new MapSqlParameterSource("groupId", groupId));
        deleteEmptyRateRows(groupId);
    }

    public void batchSetGroupRpmOverrides(long groupId, List<GroupRpmWriteEntry> entries) {
        if (!groupExists(groupId)) {
            throw new IllegalArgumentException("group not found");
        }
        List<Long> keepUserIds = entries.stream().map(GroupRpmWriteEntry::userId).distinct().toList();
        if (keepUserIds.isEmpty()) {
            clearGroupRpmOverrides(groupId);
            return;
        }
        validateUsersExist(keepUserIds);
        jdbcTemplate.update("""
                update user_group_rate_multipliers
                set rpm_override = null, updated_at = now()
                where group_id = :groupId and user_id not in (:keepUserIds)
                """, new MapSqlParameterSource()
                .addValue("groupId", groupId)
                .addValue("keepUserIds", keepUserIds));
        for (GroupRpmWriteEntry entry : entries) {
            if (entry.rpmOverride() == null) {
                jdbcTemplate.update("""
                        update user_group_rate_multipliers
                        set rpm_override = null, updated_at = now()
                        where group_id = :groupId and user_id = :userId
                        """, new MapSqlParameterSource()
                        .addValue("groupId", groupId)
                        .addValue("userId", entry.userId()));
            } else {
                jdbcTemplate.update("""
                        insert into user_group_rate_multipliers (user_id, group_id, rpm_override, created_at, updated_at)
                        values (:userId, :groupId, :rpmOverride, now(), now())
                        on conflict (user_id, group_id)
                        do update set rpm_override = excluded.rpm_override, updated_at = excluded.updated_at
                        """, new MapSqlParameterSource()
                        .addValue("userId", entry.userId())
                        .addValue("groupId", groupId)
                        .addValue("rpmOverride", entry.rpmOverride()));
            }
        }
        deleteEmptyRateRows(groupId);
    }

    public void updateGroupSortOrders(List<GroupSortOrderEntry> updates) {
        List<GroupSortOrderEntry> deduped = new ArrayList<>();
        Map<Long, Integer> latest = new LinkedHashMap<>();
        for (GroupSortOrderEntry update : updates) {
            if (update.id() <= 0) {
                continue;
            }
            latest.put(update.id(), update.sortOrder());
        }
        for (Map.Entry<Long, Integer> entry : latest.entrySet()) {
            deduped.add(new GroupSortOrderEntry(entry.getKey(), entry.getValue()));
        }
        if (deduped.isEmpty()) {
            return;
        }
        validateGroupsExist(deduped.stream().map(GroupSortOrderEntry::id).toList());
        for (GroupSortOrderEntry entry : deduped) {
            jdbcTemplate.update("""
                    update groups
                    set sort_order = :sortOrder, updated_at = now()
                    where id = :id and deleted_at is null
                    """, new MapSqlParameterSource()
                    .addValue("id", entry.id())
                    .addValue("sortOrder", entry.sortOrder()));
        }
    }

    public List<GroupUsageSummaryResponse> getUsageSummary(Instant todayStart) {
        return jdbcTemplate.query("""
                select g.id as group_id,
                       coalesce(sum(case when ul.created_at >= :todayStart then ul.actual_cost else 0 end), 0) as today_cost,
                       coalesce(sum(ul.actual_cost), 0) as total_cost
                from groups g
                left join usage_logs ul on ul.group_id = g.id
                where g.deleted_at is null
                group by g.id
                order by g.sort_order asc, g.id asc
                """, new MapSqlParameterSource("todayStart", Timestamp.from(todayStart)), (rs, rowNum) -> new GroupUsageSummaryResponse(
                rs.getLong("group_id"),
                rs.getDouble("today_cost"),
                rs.getDouble("total_cost")
        ));
    }

    public List<GroupCapacitySummaryResponse> getCapacitySummary() {
        return jdbcTemplate.query("""
                select g.id as group_id,
                       coalesce(sum(case when a.status = 'active' and a.schedulable = true then a.concurrency else 0 end), 0) as concurrency_max,
                       0 as concurrency_used,
                       coalesce(sum(
                           case
                               when a.status = 'active' and a.schedulable = true then
                                   greatest(0, coalesce((a.extra ->> 'max_sessions')::int, 0))
                               else 0
                           end
                       ), 0) as sessions_max,
                       0 as sessions_used,
                       coalesce(sum(
                           case
                               when a.status = 'active' and a.schedulable = true then
                                   greatest(0, coalesce((a.extra ->> 'base_rpm')::int, 0))
                               else 0
                           end
                       ), 0) as rpm_max,
                       0 as rpm_used
                from groups g
                left join account_groups ag on ag.group_id = g.id
                left join accounts a on a.id = ag.account_id and a.deleted_at is null
                where g.deleted_at is null and g.status = 'active'
                group by g.id, g.sort_order
                order by g.sort_order asc, g.id asc
                """, new MapSqlParameterSource(), (rs, rowNum) -> new GroupCapacitySummaryResponse(
                rs.getLong("group_id"),
                rs.getInt("concurrency_used"),
                rs.getInt("concurrency_max"),
                rs.getInt("sessions_used"),
                rs.getInt("sessions_max"),
                rs.getInt("rpm_used"),
                rs.getInt("rpm_max")
        ));
    }

    public boolean groupNameExists(String name, Long excludeId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("name", name);
        String sql = """
                select exists(
                    select 1
                    from groups
                    where deleted_at is null
                      and lower(name) = lower(:name)
                """;
        if (excludeId != null) {
            sql += "\n      and id <> :excludeId";
            params.addValue("excludeId", excludeId);
        }
        sql += "\n)";
        Boolean exists = jdbcTemplate.queryForObject(sql, params, Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public Optional<GroupSnapshot> findFallbackGroup(long id) {
        return getGroupSnapshot(id);
    }

    public List<Long> getAccountIdsByGroupIds(List<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                select distinct ag.account_id
                from account_groups ag
                where ag.group_id in (:groupIds)
                order by ag.account_id asc
                """, new MapSqlParameterSource("groupIds", groupIds), (rs, rowNum) -> rs.getLong("account_id"));
    }

    public List<AccountSnapshot> getAccountsByIds(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                select id, platform, type
                from accounts
                where id in (:accountIds) and deleted_at is null
                """, new MapSqlParameterSource("accountIds", accountIds), (rs, rowNum) -> new AccountSnapshot(
                rs.getLong("id"),
                defaultString(rs.getString("platform")),
                defaultString(rs.getString("type"))
        ));
    }

    public void clearGroupAccounts(long groupId) {
        jdbcTemplate.update("""
                delete from account_groups
                where group_id = :groupId
                """, new MapSqlParameterSource("groupId", groupId));
    }

    public void bindAccountsToGroup(long groupId, List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return;
        }
        for (Long accountId : new LinkedHashSet<>(accountIds)) {
            jdbcTemplate.update("""
                    insert into account_groups (account_id, group_id, priority, created_at)
                    values (:accountId, :groupId, 50, now())
                    on conflict do nothing
                    """, new MapSqlParameterSource()
                    .addValue("accountId", accountId)
                    .addValue("groupId", groupId));
        }
    }

    private void validateUsersExist(List<Long> userIds) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from users
                where deleted_at is null and id in (:userIds)
                """, new MapSqlParameterSource("userIds", userIds), Long.class);
        if (count == null || count != userIds.stream().distinct().count()) {
            throw new IllegalArgumentException("one or more users not found");
        }
    }

    private void validateGroupsExist(List<Long> groupIds) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from groups
                where deleted_at is null and id in (:groupIds)
                """, new MapSqlParameterSource("groupIds", groupIds), Long.class);
        if (count == null || count != groupIds.stream().distinct().count()) {
            throw new IllegalArgumentException("group not found");
        }
    }

    private void deleteEmptyRateRows(long groupId) {
        jdbcTemplate.update("""
                delete from user_group_rate_multipliers
                where group_id = :groupId and rate_multiplier is null and rpm_override is null
                """, new MapSqlParameterSource("groupId", groupId));
    }

    private MapSqlParameterSource groupWriteParams(GroupWriteModel model) {
        return new MapSqlParameterSource()
                .addValue("name", model.name())
                .addValue("description", model.description())
                .addValue("platform", model.platform())
                .addValue("rateMultiplier", model.rateMultiplier())
                .addValue("isExclusive", model.isExclusive())
                .addValue("status", model.status())
                .addValue("subscriptionType", model.subscriptionType())
                .addValue("dailyLimitUsd", model.dailyLimitUsd())
                .addValue("weeklyLimitUsd", model.weeklyLimitUsd())
                .addValue("monthlyLimitUsd", model.monthlyLimitUsd())
                .addValue("allowImageGeneration", model.allowImageGeneration())
                .addValue("imageRateIndependent", model.imageRateIndependent())
                .addValue("imageRateMultiplier", model.imageRateMultiplier())
                .addValue("imagePrice1k", model.imagePrice1k())
                .addValue("imagePrice2k", model.imagePrice2k())
                .addValue("imagePrice4k", model.imagePrice4k())
                .addValue("claudeCodeOnly", model.claudeCodeOnly())
                .addValue("fallbackGroupId", model.fallbackGroupId())
                .addValue("fallbackGroupIdOnInvalidRequest", model.fallbackGroupIdOnInvalidRequest())
                .addValue("modelRouting", jsonHelper.writeJson(model.modelRouting() == null ? Map.of() : model.modelRouting()))
                .addValue("modelRoutingEnabled", model.modelRoutingEnabled())
                .addValue("mcpXmlInject", model.mcpXmlInject())
                .addValue("supportedModelScopes", jsonHelper.writeJson(model.supportedModelScopes() == null ? List.of() : model.supportedModelScopes()))
                .addValue("allowMessagesDispatch", model.allowMessagesDispatch())
                .addValue("requireOauthOnly", model.requireOauthOnly())
                .addValue("requirePrivacySet", model.requirePrivacySet())
                .addValue("defaultMappedModel", model.defaultMappedModel())
                .addValue("messagesDispatchModelConfig", jsonHelper.writeJson(toMessagesDispatchMap(model.messagesDispatchModelConfig())))
                .addValue("rpmLimit", model.rpmLimit())
                .addValue("sortOrder", model.sortOrder());
    }

    private Map<String, Object> toMessagesDispatchMap(AdminGroupResponse.MessagesDispatchModelConfig config) {
        if (config == null) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("opus_mapped_model", defaultString(config.opus_mapped_model()));
        value.put("sonnet_mapped_model", defaultString(config.sonnet_mapped_model()));
        value.put("haiku_mapped_model", defaultString(config.haiku_mapped_model()));
        value.put("exact_model_mappings", config.exact_model_mappings() == null ? Map.of() : config.exact_model_mappings());
        return value;
    }

    private String buildOrderBy(String sortBy, String sortOrder) {
        String direction = "desc".equalsIgnoreCase(sortOrder) ? "desc" : "asc";
        String field = switch (sortBy == null ? "" : sortBy.trim()) {
            case "name" -> "g.name";
            case "platform" -> "g.platform";
            case "billing_type", "subscription_type" -> "g.subscription_type";
            case "rate_multiplier" -> "g.rate_multiplier";
            case "is_exclusive" -> "g.is_exclusive";
            case "status" -> "g.status";
            case "created_at" -> "g.created_at";
            case "id" -> "g.id";
            case "account_count" -> "coalesce(aggr.account_count, 0)";
            default -> "g.sort_order";
        };
        return "\norder by " + field + " " + direction + ", g.id asc\n";
    }

    private AdminGroupResponse mapGroup(ResultSet rs) throws SQLException {
        return new AdminGroupResponse(
                rs.getLong("id"),
                defaultString(rs.getString("name")),
                rs.getString("description"),
                defaultString(rs.getString("platform")),
                toDouble(rs.getBigDecimal("rate_multiplier"), 0.0d),
                rs.getInt("rpm_limit"),
                rs.getBoolean("is_exclusive"),
                defaultString(rs.getString("status")),
                defaultString(rs.getString("subscription_type")),
                toNullableDouble(rs.getBigDecimal("daily_limit_usd")),
                toNullableDouble(rs.getBigDecimal("weekly_limit_usd")),
                toNullableDouble(rs.getBigDecimal("monthly_limit_usd")),
                rs.getBoolean("allow_image_generation"),
                rs.getBoolean("image_rate_independent"),
                toDouble(rs.getBigDecimal("image_rate_multiplier"), 0.0d),
                toNullableDouble(rs.getBigDecimal("image_price_1k")),
                toNullableDouble(rs.getBigDecimal("image_price_2k")),
                toNullableDouble(rs.getBigDecimal("image_price_4k")),
                rs.getBoolean("claude_code_only"),
                rs.getObject("fallback_group_id", Long.class),
                rs.getObject("fallback_group_id_on_invalid_request", Long.class),
                rs.getBoolean("allow_messages_dispatch"),
                defaultString(rs.getString("default_mapped_model")),
                parseMessagesDispatchConfig(rs.getString("messages_dispatch_model_config")),
                rs.getBoolean("require_oauth_only"),
                rs.getBoolean("require_privacy_set"),
                parseModelRouting(rs.getString("model_routing")),
                rs.getBoolean("model_routing_enabled"),
                rs.getBoolean("mcp_xml_inject"),
                parseSupportedModelScopes(rs.getString("supported_model_scopes")),
                rs.getLong("account_count"),
                rs.getLong("active_account_count"),
                rs.getLong("rate_limited_account_count"),
                rs.getInt("sort_order"),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private Map<String, List<Long>> parseModelRouting(String raw) {
        Map<String, Object> parsed = jsonHelper.readObjectMap(raw);
        if (parsed.isEmpty()) {
            return null;
        }
        Map<String, List<Long>> routing = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            if (!(entry.getValue() instanceof List<?> values)) {
                continue;
            }
            List<Long> ids = values.stream()
                    .map(value -> {
                        if (value instanceof Number number) {
                            return number.longValue();
                        }
                        try {
                            return Long.parseLong(String.valueOf(value));
                        } catch (Exception ex) {
                            return null;
                        }
                    })
                    .filter(id -> id != null && id > 0)
                    .toList();
            if (!ids.isEmpty()) {
                routing.put(entry.getKey(), ids);
            }
        }
        return routing.isEmpty() ? null : routing;
    }

    private List<String> parseSupportedModelScopes(String raw) {
        List<String> values = jsonHelper.readStringList(raw);
        if (values == null || values.isEmpty()) {
            return List.of("claude", "gemini_text", "gemini_image");
        }
        return values;
    }

    private AdminGroupResponse.MessagesDispatchModelConfig parseMessagesDispatchConfig(String raw) {
        Map<String, Object> map = jsonHelper.readObjectMap(raw);
        Map<String, String> exactMappings = new LinkedHashMap<>();
        Object exact = map.get("exact_model_mappings");
        if (exact instanceof Map<?, ?> source) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                String key = String.valueOf(entry.getKey()).trim();
                String value = String.valueOf(entry.getValue()).trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    exactMappings.put(key, value);
                }
            }
        }
        return new AdminGroupResponse.MessagesDispatchModelConfig(
                defaultStringOr((String) map.get("opus_mapped_model"), "gpt-5.4"),
                defaultStringOr((String) map.get("sonnet_mapped_model"), "gpt-5.3-codex"),
                defaultStringOr((String) map.get("haiku_mapped_model"), "gpt-5.4-mini"),
                exactMappings
        );
    }

    private String maskKeyPrefix(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return key.length() <= 10 ? key : key.substring(0, 10);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String defaultStringOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private Double toNullableDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private double toDouble(BigDecimal value, double defaultValue) {
        return value == null ? defaultValue : value.doubleValue();
    }

    public record GroupSnapshot(
            long id,
            String name,
            String platform,
            String status,
            String subscriptionType,
            boolean claudeCodeOnly,
            Long fallbackGroupId,
            Long fallbackGroupIdOnInvalidRequest
    ) {
    }

    public record GroupWriteModel(
            String name,
            String description,
            String platform,
            double rateMultiplier,
            boolean isExclusive,
            String status,
            String subscriptionType,
            Double dailyLimitUsd,
            Double weeklyLimitUsd,
            Double monthlyLimitUsd,
            boolean allowImageGeneration,
            boolean imageRateIndependent,
            double imageRateMultiplier,
            Double imagePrice1k,
            Double imagePrice2k,
            Double imagePrice4k,
            boolean claudeCodeOnly,
            Long fallbackGroupId,
            Long fallbackGroupIdOnInvalidRequest,
            Map<String, List<Long>> modelRouting,
            boolean modelRoutingEnabled,
            boolean mcpXmlInject,
            List<String> supportedModelScopes,
            boolean allowMessagesDispatch,
            boolean requireOauthOnly,
            boolean requirePrivacySet,
            String defaultMappedModel,
            AdminGroupResponse.MessagesDispatchModelConfig messagesDispatchModelConfig,
            int rpmLimit,
            int sortOrder
    ) {
    }

    public record GroupRateWriteEntry(long userId, double rateMultiplier) {
    }

    public record GroupRpmWriteEntry(long userId, Integer rpmOverride) {
    }

    public record GroupSortOrderEntry(long id, int sortOrder) {
    }

    public record AccountSnapshot(long id, String platform, String type) {
    }
}
