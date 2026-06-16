package org.apiprivaterouter.javabackend.admin.repository;

import org.apiprivaterouter.javabackend.admin.model.AdminUserResponse;
import org.apiprivaterouter.javabackend.admin.model.AdminUserUsageResponse;
import org.apiprivaterouter.javabackend.admin.model.AdminBoundAuthIdentityResponse;
import org.apiprivaterouter.javabackend.admin.model.AdminUserBalanceHistoryItemResponse;
import org.apiprivaterouter.javabackend.admin.model.AdminUserBalanceHistoryResponse;
import org.apiprivaterouter.javabackend.admin.model.AdminUserRpmStatusResponse;
import org.apiprivaterouter.javabackend.admin.model.BindUserAuthIdentityRequest;
import org.apiprivaterouter.javabackend.admin.model.CreateAdminUserRequest;
import org.apiprivaterouter.javabackend.admin.model.UpdateAdminUserRequest;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.usercenter.model.NotifyEmailEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AdminUserRepository {

    private static final Logger log = LoggerFactory.getLogger(AdminUserRepository.class);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AdminUserRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    private static final java.util.Map<String, String> USER_SORT_COLUMNS = java.util.Map.of(
            "id", "id",
            "email", "email",
            "username", "username",
            "role", "role",
            "status", "status",
            "balance", "balance",
            "concurrency", "concurrency",
            "created_at", "created_at",
            "updated_at", "updated_at",
            "last_active_at", "last_active_at"
    );

    public PageResponse<AdminUserResponse> listUsers(int page, int pageSize, String status, String role, String search,
                                                      String groupName, String sortBy, String sortOrder) {
        return listUsers(page, pageSize, status, role, search, groupName, null, sortBy, sortOrder);
    }

    public PageResponse<AdminUserResponse> listUsers(int page, int pageSize, String status, String role, String search,
                                                      String groupName, Long apiKeyGroup, String sortBy, String sortOrder) {
        int offset = Math.max(page - 1, 0) * pageSize;
        String whereClause = buildUserListWhereClause(status, role, search, groupName, apiKeyGroup);
        String safeSortCol = normalizeUserSortColumn(sortBy);
        String safeSortDir = normalizeSortDirection(sortOrder);
        String countSql = """
                select count(*)
                from users
                """ + whereClause;
        String dataSql = """
                select id, email, username, role, status, balance, concurrency, rpm_limit,
                       balance_notify_enabled, balance_notify_threshold, balance_notify_extra_emails, notes,
                       last_active_at, created_at, updated_at
                from users
                """ + whereClause + """
                order by """ + safeSortCol + " " + safeSortDir + """
                limit :pageSize offset :offset
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        applyUserListFilters(params, status, role, search, groupName, apiKeyGroup);
        Long total = jdbcTemplate.queryForObject(countSql, params, Long.class);
        List<AdminUserResponse> items = jdbcTemplate.query(dataSql, params, (rs, rowNum) -> mapAdminUser(rs));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public Optional<AdminUserResponse> getUser(long id) {
        String sql = """
                select id, email, username, role, status, balance, concurrency, rpm_limit,
                       balance_notify_enabled, balance_notify_threshold, balance_notify_extra_emails, notes,
                       last_active_at, created_at, updated_at
                from users
                where id = :id and deleted_at is null
                """;
        List<AdminUserResponse> rows = jdbcTemplate.query(sql, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapAdminUser(rs));
        return rows.stream().findFirst();
    }

    private void applyUserListFilters(MapSqlParameterSource params, String status, String role, String search, String groupName, Long apiKeyGroup) {
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            params.addValue("status", normalizedStatus);
        }
        String normalizedRole = blankToNull(role);
        if (normalizedRole != null) {
            params.addValue("role", normalizedRole);
        }
        String normalizedSearch = blankToNull(search);
        if (normalizedSearch != null) {
            params.addValue("likeSearch", "%" + escapeLike(normalizedSearch) + "%");
        }
        String normalizedGroupName = blankToNull(groupName);
        if (normalizedGroupName != null) {
            params.addValue("groupName", normalizedGroupName);
        }
        if (apiKeyGroup != null) {
            params.addValue("apiKeyGroup", apiKeyGroup);
        }
    }

    private String buildUserListWhereClause(String status, String role, String search, String groupName, Long apiKeyGroup) {
        StringBuilder sql = new StringBuilder("""
                where deleted_at is null
                """);
        if (blankToNull(status) != null) {
            sql.append("\n  and status = :status");
        }
        if (blankToNull(role) != null) {
            sql.append("\n  and role = :role");
        }
        if (blankToNull(search) != null) {
            sql.append("\n  and (email ilike :likeSearch or username ilike :likeSearch)");
        }
        if (blankToNull(groupName) != null) {
            sql.append("\n  and id in (select user_id from user_allowed_groups where group_id in (select id from groups where name ilike :groupName))");
        }
        if (apiKeyGroup != null) {
            sql.append("\n  and id in (select distinct user_id from api_keys where group_id = :apiKeyGroup and deleted_at is null)");
        }
        sql.append('\n');
        return sql.toString();
    }

    private String normalizeUserSortColumn(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "created_at";
        }
        String col = USER_SORT_COLUMNS.get(sortBy.trim().toLowerCase());
        return col != null ? col : "created_at";
    }

    private String normalizeSortDirection(String sortOrder) {
        if (sortOrder != null && "asc".equalsIgnoreCase(sortOrder.trim())) {
            return "asc";
        }
        return "desc";
    }

    private AdminUserResponse mapAdminUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdminUserResponse(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("username"),
                rs.getString("role"),
                rs.getString("status"),
                toDouble(rs.getBigDecimal("balance"), 0.0d),
                defaultInt(rs.getObject("concurrency", Integer.class), 1),
                rs.getObject("rpm_limit", Integer.class),
                0,
                getAllowedGroupIds(rs.getLong("id")),
                defaultBoolean(rs.getObject("balance_notify_enabled", Boolean.class), false),
                toNullableDouble(rs.getBigDecimal("balance_notify_threshold")),
                parseNotifyEmails(rs.getString("balance_notify_extra_emails")),
                rs.getString("notes"),
                toIsoString(rs.getTimestamp("last_active_at")),
                toIsoString(rs.getTimestamp("last_active_at")),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at"))
        );
    }

    @Transactional
    public long createUser(CreateAdminUserRequest request, String passwordHash) {
        String sql = """
                insert into users (
                    email, password_hash, role, balance, concurrency, status, username, notes,
                    totp_enabled, signup_source, balance_notify_enabled, balance_notify_threshold_type,
                    balance_notify_extra_emails, total_recharged, rpm_limit, created_at, updated_at
                ) values (
                    :email, :passwordHash, 'user', :balance, :concurrency, :status, :username, :notes,
                    false, 'email', true, 'fixed', '[]', 0, :rpmLimit, now(), now()
                )
                returning id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("email", request.email())
                .addValue("passwordHash", passwordHash)
                .addValue("balance", request.balance() == null ? 0.0 : request.balance())
                .addValue("concurrency", sanitizeConcurrency(request.concurrency()))
                .addValue("username", request.username() == null ? "" : request.username().trim())
                .addValue("status", normalizeStatus(request.status()))
                .addValue("notes", request.notes() == null ? "" : request.notes().trim())
                .addValue("rpmLimit", sanitizeRpmLimit(request.rpm_limit()));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(sql, params, keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to create user");
        }
        long userId = key.longValue();
        replaceAllowedGroups(userId, request.allowed_groups());
        return userId;
    }

    @Transactional
    public void updateUser(long id, UpdateAdminUserRequest request, String passwordHash) {
        String sql = """
                update users
                set email = coalesce(:email, email),
                    password_hash = coalesce(:passwordHash, password_hash),
                    username = coalesce(:username, username),
                    notes = coalesce(:notes, notes),
                    balance = coalesce(:balance, balance),
                    concurrency = coalesce(:concurrency, concurrency),
                    rpm_limit = coalesce(:rpmLimit, rpm_limit),
                    status = coalesce(:status, status),
                    updated_at = now()
                where id = :id and deleted_at is null
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("email", blankToNull(request.email()))
                .addValue("passwordHash", passwordHash)
                .addValue("username", request.username() == null ? null : request.username().trim())
                .addValue("notes", request.notes())
                .addValue("balance", request.balance())
                .addValue("concurrency", request.concurrency() == null ? null : sanitizeConcurrency(request.concurrency()))
                .addValue("rpmLimit", request.rpm_limit() == null ? null : sanitizeRpmLimit(request.rpm_limit()))
                .addValue("status", request.status() == null ? null : normalizeStatus(request.status()));
        jdbcTemplate.update(sql, params);
        if (request.allowed_groups() != null) {
            replaceAllowedGroups(id, request.allowed_groups());
        }
    }

    public void softDeleteUser(long id) {
        jdbcTemplate.update("""
                update users
                set deleted_at = now(), updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
    }

    public void updateUserBalance(long id, double newBalance, String notes) {
        jdbcTemplate.update("""
                update users
                set balance = :balance, updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("balance", newBalance));
        insertBalanceHistory(id, newBalance, notes);
    }

    public double adjustUserBalance(long id, double delta, String notes) {
        jdbcTemplate.update("""
                update users
                set balance = balance + :delta, updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("delta", delta));
        Number balance = jdbcTemplate.queryForObject(
                "select balance from users where id = :id and deleted_at is null",
                new MapSqlParameterSource("id", id),
                Double.class
        );
        double newBalance = balance == null ? 0.0 : balance.doubleValue();
        insertBalanceHistory(id, newBalance, notes);
        return newBalance;
    }

    private void insertBalanceHistory(long userId, double amount, String notes) {
        String trimmedNotes = notes == null || notes.isBlank() ? "admin balance adjustment" : notes.trim();
        try {
            jdbcTemplate.update("""
                    insert into balance_history (user_id, type, amount, description, created_at)
                    values (:userId, 'admin_balance', :amount, :description, now())
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("amount", amount)
                    .addValue("description", trimmedNotes));
        } catch (Exception ex) {
            log.warn("failed to insert balance history for userId={}: {}", userId, ex.getMessage());
        }
    }

    public List<Long> getAllowedGroupIds(long userId) {
        return jdbcTemplate.query("""
                select group_id
                from user_allowed_groups
                where user_id = :userId
                order by group_id asc
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> rs.getLong("group_id"));
    }

    public PageResponse<Map<String, Object>> getUserApiKeys(long userId, int page, int pageSize) {
        int offset = Math.max(page - 1, 0) * pageSize;
        String countSql = """
                select count(*)
                from api_keys
                where user_id = :userId and deleted_at is null
                """;
        String dataSql = """
                select id, name, key, status, created_at
                from api_keys
                where user_id = :userId and deleted_at is null
                order by created_at desc
                limit :pageSize offset :offset
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("pageSize", pageSize)
                .addValue("offset", offset);
        Long total = jdbcTemplate.queryForObject(countSql, params, Long.class);
        List<Map<String, Object>> items = jdbcTemplate.query(dataSql, params, (rs, rowNum) -> Map.of(
                "id", rs.getLong("id"),
                "name", rs.getString("name"),
                "prefix", maskKeyPrefix(rs.getString("key")),
                "status", rs.getString("status"),
                "created_at", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant().toString()
        ));
        return new PageResponse<>(items, total == null ? 0 : total, page, pageSize);
    }

    public AdminUserUsageResponse getUserUsage(long userId, String period) {
        var params = new MapSqlParameterSource("userId", userId);
        String periodFilter = "";
        if ("today".equalsIgnoreCase(period)) {
            periodFilter = " and created_at >= date_trunc('day', now())";
        } else if ("week".equalsIgnoreCase(period)) {
            periodFilter = " and created_at >= date_trunc('week', now())";
        } else if ("month".equalsIgnoreCase(period) || period == null || period.isBlank()) {
            periodFilter = " and created_at >= date_trunc('month', now())";
        } else if ("all".equalsIgnoreCase(period)) {
            periodFilter = "";
        } else if ("year".equalsIgnoreCase(period)) {
            periodFilter = " and created_at >= date_trunc('year', now())";
        }
        String sql = """
                select count(*) as total_requests,
                       coalesce(sum(cost), 0) as total_cost,
                       coalesce(sum(total_tokens), 0) as total_tokens
                from usage_logs
                where user_id = :userId""" + periodFilter;
        return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) ->
                new AdminUserUsageResponse(
                        rs.getLong("total_requests"),
                        rs.getDouble("total_cost"),
                        rs.getLong("total_tokens")
                ));
    }

    public AdminUserBalanceHistoryResponse getUserBalanceHistory(long userId, int page, int pageSize, String type) {
        getUser(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = pageSize <= 0 ? 20 : pageSize;
        int offset = (normalizedPage - 1) * normalizedPageSize;

        String normalizedType = type == null ? "" : type.trim();
        if (!normalizedType.isEmpty() && !List.of(
                "balance",
                "affiliate_balance",
                "admin_balance",
                "concurrency",
                "admin_concurrency",
                "subscription"
        ).contains(normalizedType)) {
            throw new IllegalArgumentException("type is invalid");
        }

        if (normalizedType.isEmpty()) {
            return getMergedUserBalanceHistory(userId, normalizedPage, normalizedPageSize, offset);
        }

        if ("affiliate_balance".equals(normalizedType)) {
            return getAffiliateBalanceHistory(userId, normalizedPage, normalizedPageSize, offset);
        }

        return getRedeemBalanceHistory(userId, normalizedPage, normalizedPageSize, offset, normalizedType);
    }

    public AdminUserRpmStatusResponse getUserRpmStatus(long userId) {
        AdminUserResponse user = getUser(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));

        Long userRpmUsed = jdbcTemplate.queryForObject("""
                select count(*)
                from usage_logs
                where user_id = :userId
                  and created_at >= date_trunc('minute', now())
                """, new MapSqlParameterSource("userId", userId), Long.class);

        List<AdminUserRpmStatusResponse.AdminUserGroupRpmStatusResponse> perGroup = jdbcTemplate.query("""
                with user_groups as (
                    select distinct k.group_id
                    from api_keys k
                    where k.user_id = :userId
                      and k.group_id is not null
                      and k.deleted_at is null
                )
                select g.id as group_id,
                       g.name as group_name,
                       coalesce(ugr.rpm_override, g.rpm_limit) as rpm_limit,
                       case when ugr.rpm_override is not null then 'override' else 'group' end as source,
                       coalesce((
                           select count(*)
                           from usage_logs ul
                           where ul.user_id = :userId
                             and ul.group_id = g.id
                             and ul.created_at >= date_trunc('minute', now())
                       ), 0) as used
                from user_groups ug
                join groups g on g.id = ug.group_id
                left join user_group_rate_multipliers ugr
                       on ugr.user_id = :userId and ugr.group_id = g.id
                order by g.id asc
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) ->
                new AdminUserRpmStatusResponse.AdminUserGroupRpmStatusResponse(
                        rs.getLong("group_id"),
                        rs.getString("group_name"),
                        rs.getObject("rpm_limit", Integer.class),
                        rs.getLong("used"),
                        rs.getString("source")
                ));

        return new AdminUserRpmStatusResponse(
                userRpmUsed == null ? 0 : userRpmUsed,
                user.rpm_limit(),
                perGroup
        );
    }

    public List<Long> listAllActiveUserIds() {
        return jdbcTemplate.query("""
                select id
                from users
                where deleted_at is null
                order by id asc
                limit 50000
                """, new MapSqlParameterSource(), (rs, rowNum) -> rs.getLong("id"));
    }

    public int batchSetConcurrency(List<Long> userIds, int concurrency) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update("""
                update users
                set concurrency = :concurrency,
                    updated_at = now()
                where id in (:userIds) and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("concurrency", Math.max(concurrency, 0))
                .addValue("userIds", userIds));
    }

    public int batchAddConcurrency(List<Long> userIds, int delta) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update("""
                update users
                set concurrency = greatest(concurrency + :delta, 0),
                    updated_at = now()
                where id in (:userIds) and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("delta", delta)
                .addValue("userIds", userIds));
    }

    @Transactional
    public AdminBoundAuthIdentityResponse bindUserAuthIdentity(long userId, BindUserAuthIdentityRequest request) {
        getUser(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));

        String providerType = normalizeProviderType(request == null ? null : request.provider_type());
        String providerKey = trimToEmpty(request == null ? null : request.provider_key());
        String providerSubject = trimToEmpty(request == null ? null : request.provider_subject());
        if (providerType.isEmpty()) {
            throw new IllegalArgumentException("provider_type must be one of email, linuxdo, oidc, or wechat");
        }
        if (providerKey.isEmpty() || providerSubject.isEmpty()) {
            throw new IllegalArgumentException("provider_type, provider_key, and provider_subject are required");
        }

        String canonicalProviderKey = canonicalProviderKey(providerType, providerKey);
        List<String> compatibleProviderKeys = compatibleProviderKeys(providerType, providerKey);
        String issuer = trimToNull(request == null ? null : request.issuer());
        Map<String, Object> metadata = copyMap(request == null ? null : request.metadata());

        List<AuthIdentityRow> identityRows = jdbcTemplate.query("""
                select id, user_id, provider_type, provider_key, provider_subject, issuer,
                       metadata::text as metadata_json, verified_at, created_at, updated_at
                from auth_identities
                where provider_type = :providerType
                  and provider_key in (:providerKeys)
                  and provider_subject = :providerSubject
                order by id asc
                """, new MapSqlParameterSource()
                .addValue("providerType", providerType)
                .addValue("providerKeys", compatibleProviderKeys)
                .addValue("providerSubject", providerSubject), (rs, rowNum) -> mapAuthIdentityRow(rs));
        for (AuthIdentityRow row : identityRows) {
            if (row.userId() != userId) {
                throw new HttpStatusException(409, "auth identity already belongs to another user");
            }
        }

        AuthIdentityRow ownedIdentity = selectOwnedIdentity(identityRows, userId);
        long identityId;
        if (ownedIdentity == null) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update("""
                    insert into auth_identities (
                        user_id, provider_type, provider_key, provider_subject, verified_at, issuer, metadata, created_at, updated_at
                    ) values (
                        :userId, :providerType, :providerKey, :providerSubject, now(), :issuer, cast(:metadataJson as jsonb), now(), now()
                    )
                    returning id
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("providerType", providerType)
                    .addValue("providerKey", canonicalProviderKey)
                    .addValue("providerSubject", providerSubject)
                    .addValue("issuer", issuer)
                    .addValue("metadataJson", jsonHelper.writeJson(metadata)), keyHolder, new String[]{"id"});
            identityId = requireGeneratedId(keyHolder, "failed to create auth identity");
        } else {
            jdbcTemplate.update("""
                    update auth_identities
                    set user_id = :userId,
                        provider_key = :providerKey,
                        verified_at = now(),
                        issuer = coalesce(:issuer, issuer),
                        metadata = case when :metadataJson is null then metadata else cast(:metadataJson as jsonb) end,
                        updated_at = now()
                    where id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", ownedIdentity.id())
                    .addValue("userId", userId)
                    .addValue("providerKey", canonicalProviderKey)
                    .addValue("issuer", issuer)
                    .addValue("metadataJson", metadata == null ? null : jsonHelper.writeJson(metadata)));
            identityId = ownedIdentity.id();
        }

        AdminBoundAuthIdentityResponse.AdminBoundAuthIdentityChannelResponse channelResponse = null;
        BindUserAuthIdentityRequest.BindUserAuthIdentityChannelRequest channelRequest = request == null ? null : request.channel();
        if (channelRequest != null) {
            String channel = trimToEmpty(channelRequest.channel());
            String channelAppId = trimToEmpty(channelRequest.channel_app_id());
            String channelSubject = trimToEmpty(channelRequest.channel_subject());
            if (channel.isEmpty() || channelAppId.isEmpty() || channelSubject.isEmpty()) {
                throw new IllegalArgumentException("channel, channel_app_id, and channel_subject are required when channel binding is provided");
            }
            Map<String, Object> channelMetadata = copyMap(channelRequest.metadata());

            List<AuthIdentityChannelRow> channelRows = jdbcTemplate.query("""
                    select channel.id, channel.identity_id, channel.provider_type, channel.provider_key,
                           channel.channel, channel.channel_app_id, channel.channel_subject,
                           channel.metadata::text as metadata_json, channel.created_at, channel.updated_at,
                           identity.user_id
                    from auth_identity_channels channel
                    join auth_identities identity on identity.id = channel.identity_id
                    where channel.provider_type = :providerType
                      and channel.provider_key in (:providerKeys)
                      and channel.channel = :channel
                      and channel.channel_app_id = :channelAppId
                      and channel.channel_subject = :channelSubject
                    order by channel.id asc
                    """, new MapSqlParameterSource()
                    .addValue("providerType", providerType)
                    .addValue("providerKeys", compatibleProviderKeys)
                    .addValue("channel", channel)
                    .addValue("channelAppId", channelAppId)
                    .addValue("channelSubject", channelSubject), (rs, rowNum) -> mapAuthIdentityChannelRow(rs));
            for (AuthIdentityChannelRow row : channelRows) {
                if (row.userId() != userId) {
                    throw new HttpStatusException(409, "auth identity channel already belongs to another user");
                }
            }

            AuthIdentityChannelRow ownedChannel = selectOwnedChannel(channelRows, userId);
            if (ownedChannel == null) {
                jdbcTemplate.update("""
                        insert into auth_identity_channels (
                            identity_id, provider_type, provider_key, channel, channel_app_id, channel_subject, metadata, created_at, updated_at
                        ) values (
                            :identityId, :providerType, :providerKey, :channel, :channelAppId, :channelSubject, cast(:metadataJson as jsonb), now(), now()
                        )
                        """, new MapSqlParameterSource()
                        .addValue("identityId", identityId)
                        .addValue("providerType", providerType)
                        .addValue("providerKey", canonicalProviderKey)
                        .addValue("channel", channel)
                        .addValue("channelAppId", channelAppId)
                        .addValue("channelSubject", channelSubject)
                        .addValue("metadataJson", jsonHelper.writeJson(channelMetadata)));
            } else {
                jdbcTemplate.update("""
                        update auth_identity_channels
                        set identity_id = :identityId,
                            provider_key = :providerKey,
                            metadata = case when :metadataJson is null then metadata else cast(:metadataJson as jsonb) end,
                            updated_at = now()
                        where id = :id
                        """, new MapSqlParameterSource()
                        .addValue("id", ownedChannel.id())
                        .addValue("identityId", identityId)
                        .addValue("providerKey", canonicalProviderKey)
                        .addValue("metadataJson", channelMetadata == null ? null : jsonHelper.writeJson(channelMetadata)));
            }

            channelResponse = jdbcTemplate.query("""
                    select channel, channel_app_id, channel_subject, metadata::text as metadata_json, created_at, updated_at
                    from auth_identity_channels
                    where identity_id = :identityId
                      and provider_type = :providerType
                      and channel = :channel
                      and channel_app_id = :channelAppId
                      and channel_subject = :channelSubject
                    order by id desc
                    limit 1
                    """, new MapSqlParameterSource()
                    .addValue("identityId", identityId)
                    .addValue("providerType", providerType)
                    .addValue("channel", channel)
                    .addValue("channelAppId", channelAppId)
                    .addValue("channelSubject", channelSubject), rs -> rs.next()
                    ? new AdminBoundAuthIdentityResponse.AdminBoundAuthIdentityChannelResponse(
                    rs.getString("channel"),
                    rs.getString("channel_app_id"),
                    rs.getString("channel_subject"),
                    jsonHelper.readObjectMap(rs.getString("metadata_json")),
                    toIsoString(rs.getTimestamp("created_at")),
                    toIsoString(rs.getTimestamp("updated_at")))
                    : null);
        }

        AdminBoundAuthIdentityResponse.AdminBoundAuthIdentityChannelResponse finalChannelResponse = channelResponse;

        return jdbcTemplate.query("""
                select user_id, provider_type, provider_key, provider_subject, verified_at, issuer,
                       metadata::text as metadata_json, created_at, updated_at
                from auth_identities
                where id = :id
                """, new MapSqlParameterSource("id", identityId), rs -> rs.next()
                ? new AdminBoundAuthIdentityResponse(
                rs.getLong("user_id"),
                rs.getString("provider_type"),
                rs.getString("provider_key"),
                rs.getString("provider_subject"),
                toIsoString(rs.getTimestamp("verified_at")),
                rs.getString("issuer"),
                jsonHelper.readObjectMap(rs.getString("metadata_json")),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at")),
                finalChannelResponse
        ) : null);
    }

    @Transactional
    public int replaceUserGroup(long userId, long oldGroupId, long newGroupId) {
        getUser(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        validateGroupExists(oldGroupId);
        validateGroupExists(newGroupId);

        jdbcTemplate.update("""
                insert into user_allowed_groups (user_id, group_id, created_at)
                values (:userId, :groupId, now())
                on conflict do nothing
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("groupId", newGroupId));

        int migrated = jdbcTemplate.update("""
                update api_keys
                set group_id = :newGroupId,
                    updated_at = now()
                where user_id = :userId
                  and group_id = :oldGroupId
                  and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("oldGroupId", oldGroupId)
                .addValue("newGroupId", newGroupId));

        jdbcTemplate.update("""
                delete from user_allowed_groups
                where user_id = :userId and group_id = :oldGroupId
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("oldGroupId", oldGroupId));
        return migrated;
    }

    private String maskKeyPrefix(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return key.length() <= 10 ? key : key.substring(0, 10);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private boolean defaultBoolean(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private Double toNullableDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private double toDouble(BigDecimal value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private int sanitizeConcurrency(Integer concurrency) {
        if (concurrency == null || concurrency <= 0) {
            return 1;
        }
        return concurrency;
    }

    private int sanitizeRpmLimit(Integer rpmLimit) {
        if (rpmLimit == null || rpmLimit < 0) {
            return 0;
        }
        return rpmLimit;
    }

    private String normalizeStatus(String status) {
        String normalized = blankToNull(status);
        if (normalized == null) {
            return "active";
        }
        String lower = normalized.toLowerCase();
        return "disabled".equals(lower) ? "disabled" : "active";
    }

    private void replaceAllowedGroups(long userId, List<Long> groupIds) {
        jdbcTemplate.update("""
                delete from user_allowed_groups
                where user_id = :userId
                """, new MapSqlParameterSource("userId", userId));
        if (groupIds == null || groupIds.isEmpty()) {
            return;
        }
        for (Long groupId : groupIds) {
            if (groupId == null) {
                continue;
            }
            jdbcTemplate.update("""
                    insert into user_allowed_groups (user_id, group_id, created_at)
                    values (:userId, :groupId, now())
                    on conflict do nothing
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("groupId", groupId));
        }
    }

    private List<NotifyEmailEntry> parseNotifyEmails(String raw) {
        return jsonHelper.readObjectList(raw).stream()
                .map(item -> new NotifyEmailEntry(
                        String.valueOf(item.getOrDefault("email", "")),
                        Boolean.parseBoolean(String.valueOf(item.getOrDefault("disabled", false)))
                ))
                .toList();
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private AdminUserBalanceHistoryResponse getRedeemBalanceHistory(long userId, int page, int pageSize, int offset, String type) {
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from redeem_codes
                where used_by = :userId
                  and (:type is null or :type = '' or type = :type)
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("type", type == null || type.isBlank() ? null : type), Long.class);
        List<AdminUserBalanceHistoryItemResponse> items = jdbcTemplate.query("""
                select rc.id, rc.code, rc.type, rc.value, rc.status, rc.used_by, rc.used_at, rc.created_at,
                       rc.group_id, coalesce(rc.validity_days, 0) as validity_days, rc.notes,
                       u.id as user_id, u.email as user_email,
                       g.id as resolved_group_id, g.name as group_name
                from redeem_codes rc
                left join users u on u.id = rc.used_by
                left join groups g on g.id = rc.group_id
                where rc.used_by = :userId
                  and (:type is null or :type = '' or rc.type = :type)
                order by coalesce(rc.used_at, rc.created_at) desc, rc.id desc
                limit :limit offset :offset
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("type", type == null || type.isBlank() ? null : type)
                .addValue("limit", pageSize)
                .addValue("offset", offset), (rs, rowNum) -> mapBalanceHistoryItem(rs, false));
        return new AdminUserBalanceHistoryResponse(
                items,
                total == null ? 0 : total,
                page,
                pageSize,
                calculatePages(total == null ? 0 : total, pageSize),
                getTotalRecharged(userId)
        );
    }

    private AdminUserBalanceHistoryResponse getAffiliateBalanceHistory(long userId, int page, int pageSize, int offset) {
        Long total = jdbcTemplate.queryForObject("""
                select count(*)
                from user_affiliate_ledger
                where user_id = :userId
                  and action = 'transfer'
                """, new MapSqlParameterSource("userId", userId), Long.class);
        List<AdminUserBalanceHistoryItemResponse> items = jdbcTemplate.query("""
                select id, amount, created_at
                from user_affiliate_ledger
                where user_id = :userId
                  and action = 'transfer'
                order by created_at desc, id desc
                limit :limit offset :offset
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", pageSize)
                .addValue("offset", offset), (rs, rowNum) ->
                new AdminUserBalanceHistoryItemResponse(
                        -rs.getLong("id"),
                        "AFF-" + rs.getLong("id"),
                        "affiliate_balance",
                        rs.getDouble("amount"),
                        "used",
                        userId,
                        toIsoString(rs.getTimestamp("created_at")),
                        toIsoString(rs.getTimestamp("created_at")),
                        null,
                        0,
                        "",
                        null,
                        null
                ));
        return new AdminUserBalanceHistoryResponse(
                items,
                total == null ? 0 : total,
                page,
                pageSize,
                calculatePages(total == null ? 0 : total, pageSize),
                getTotalRecharged(userId)
        );
    }

    private AdminUserBalanceHistoryResponse getMergedUserBalanceHistory(long userId, int page, int pageSize, int offset) {
        int needed = offset + pageSize;
        List<AdminUserBalanceHistoryItemResponse> redeemItems = jdbcTemplate.query("""
                select rc.id, rc.code, rc.type, rc.value, rc.status, rc.used_by, rc.used_at, rc.created_at,
                       rc.group_id, coalesce(rc.validity_days, 0) as validity_days, rc.notes,
                       u.id as user_id, u.email as user_email,
                       g.id as resolved_group_id, g.name as group_name
                from redeem_codes rc
                left join users u on u.id = rc.used_by
                left join groups g on g.id = rc.group_id
                where rc.used_by = :userId
                order by coalesce(rc.used_at, rc.created_at) desc, rc.id desc
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", needed), (rs, rowNum) -> mapBalanceHistoryItem(rs, false));
        List<AdminUserBalanceHistoryItemResponse> affiliateItems = jdbcTemplate.query("""
                select id, amount, created_at
                from user_affiliate_ledger
                where user_id = :userId
                  and action = 'transfer'
                order by created_at desc, id desc
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", needed), (rs, rowNum) ->
                new AdminUserBalanceHistoryItemResponse(
                        -rs.getLong("id"),
                        "AFF-" + rs.getLong("id"),
                        "affiliate_balance",
                        rs.getDouble("amount"),
                        "used",
                        userId,
                        toIsoString(rs.getTimestamp("created_at")),
                        toIsoString(rs.getTimestamp("created_at")),
                        null,
                        0,
                        "",
                        null,
                        null
                ));
        List<AdminUserBalanceHistoryItemResponse> merged = new ArrayList<>(redeemItems.size() + affiliateItems.size());
        merged.addAll(redeemItems);
        merged.addAll(affiliateItems);
        merged.sort((a, b) -> {
            Instant aTime = parseHistoryTime(a);
            Instant bTime = parseHistoryTime(b);
            int timeCompare = bTime.compareTo(aTime);
            if (timeCompare != 0) {
                return timeCompare;
            }
            return Long.compare(b.id(), a.id());
        });
        int fromIndex = Math.min(offset, merged.size());
        int toIndex = Math.min(offset + pageSize, merged.size());
        List<AdminUserBalanceHistoryItemResponse> items = merged.subList(fromIndex, toIndex);

        long redeemTotal = jdbcTemplate.queryForObject("""
                select count(*) from redeem_codes where used_by = :userId
                """, new MapSqlParameterSource("userId", userId), Long.class);
        long affiliateTotal = jdbcTemplate.queryForObject("""
                select count(*) from user_affiliate_ledger where user_id = :userId and action = 'transfer'
                """, new MapSqlParameterSource("userId", userId), Long.class);
        long total = redeemTotal + affiliateTotal;

        return new AdminUserBalanceHistoryResponse(
                items,
                total,
                page,
                pageSize,
                calculatePages(total, pageSize),
                getTotalRecharged(userId)
        );
    }

    private AdminUserBalanceHistoryItemResponse mapBalanceHistoryItem(java.sql.ResultSet rs, boolean affiliate) throws java.sql.SQLException {
        Map<String, Object> user = null;
        Long usedBy = rs.getObject("used_by", Long.class);
        if (usedBy != null) {
            user = Map.of(
                    "id", usedBy,
                    "email", defaultString(rs.getString("user_email"))
            );
        }
        Map<String, Object> group = null;
        Long groupId = rs.getObject("resolved_group_id", Long.class);
        if (groupId != null) {
            group = Map.of(
                    "id", groupId,
                    "name", defaultString(rs.getString("group_name"))
            );
        }
        return new AdminUserBalanceHistoryItemResponse(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("type"),
                rs.getDouble("value"),
                rs.getString("status"),
                usedBy,
                toIsoString(rs.getTimestamp("used_at")),
                toIsoString(rs.getTimestamp("created_at")),
                rs.getObject("group_id", Long.class),
                rs.getInt("validity_days"),
                visibleNotes(rs.getString("type"), rs.getString("notes")),
                user,
                group
        );
    }

    private double getTotalRecharged(long userId) {
        Double total = jdbcTemplate.queryForObject("""
                select coalesce(sum(value), 0)
                from redeem_codes
                where used_by = :userId
                  and type = 'balance'
                  and value > 0
                """, new MapSqlParameterSource("userId", userId), Double.class);
        return total == null ? 0.0 : total;
    }

    private String visibleNotes(String type, String notes) {
        if (notes == null || notes.isBlank()) {
            return "";
        }
        return ("admin_balance".equals(type) || "admin_concurrency".equals(type)) ? notes : "";
    }

    private int calculatePages(long total, int pageSize) {
        return pageSize <= 0 ? 0 : (int) Math.ceil((double) total / pageSize);
    }

    private Instant parseHistoryTime(AdminUserBalanceHistoryItemResponse item) {
        String raw = item.used_at() == null || item.used_at().isBlank() ? item.created_at() : item.used_at();
        return raw == null || raw.isBlank() ? Instant.EPOCH : Instant.parse(raw);
    }

    private void validateGroupExists(long groupId) {
        Long id = jdbcTemplate.query("""
                select id from groups where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", groupId), rs -> rs.next() ? rs.getLong("id") : null);
        if (id == null) {
            throw new IllegalArgumentException("group not found");
        }
    }

    private AuthIdentityRow mapAuthIdentityRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AuthIdentityRow(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("provider_type"),
                rs.getString("provider_key"),
                rs.getString("provider_subject"),
                rs.getString("issuer"),
                jsonHelper.readObjectMap(rs.getString("metadata_json")),
                rs.getObject("verified_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private AuthIdentityChannelRow mapAuthIdentityChannelRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AuthIdentityChannelRow(
                rs.getLong("id"),
                rs.getLong("identity_id"),
                rs.getString("provider_type"),
                rs.getString("provider_key"),
                rs.getString("channel"),
                rs.getString("channel_app_id"),
                rs.getString("channel_subject"),
                jsonHelper.readObjectMap(rs.getString("metadata_json")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getLong("user_id")
        );
    }

    private AuthIdentityRow selectOwnedIdentity(List<AuthIdentityRow> rows, long userId) {
        AuthIdentityRow selected = null;
        for (AuthIdentityRow row : rows) {
            if (row.userId() != userId) {
                continue;
            }
            if (selected == null || providerKeyRank(row.providerType(), row.providerKey()) < providerKeyRank(selected.providerType(), selected.providerKey())) {
                selected = row;
            }
        }
        return selected;
    }

    private AuthIdentityChannelRow selectOwnedChannel(List<AuthIdentityChannelRow> rows, long userId) {
        AuthIdentityChannelRow selected = null;
        for (AuthIdentityChannelRow row : rows) {
            if (row.userId() != userId) {
                continue;
            }
            if (selected == null || providerKeyRank(row.providerType(), row.providerKey()) < providerKeyRank(selected.providerType(), selected.providerKey())) {
                selected = row;
            }
        }
        return selected;
    }

    private String normalizeProviderType(String providerType) {
        String normalized = trimToEmpty(providerType).toLowerCase();
        return switch (normalized) {
            case "email", "linuxdo", "oidc", "wechat" -> normalized;
            default -> "";
        };
    }

    private List<String> compatibleProviderKeys(String providerType, String providerKey) {
        if (!"wechat".equals(providerType)) {
            return List.of(providerKey);
        }
        List<String> keys = new ArrayList<>();
        keys.add(providerKey);
        if (!"wechat-main".equalsIgnoreCase(providerKey)) {
            keys.add("wechat-main");
        }
        if (!"wechat".equalsIgnoreCase(providerKey)) {
            keys.add("wechat");
        }
        return keys.stream().distinct().toList();
    }

    private String canonicalProviderKey(String providerType, String providerKey) {
        if (!"wechat".equals(providerType)) {
            return providerKey;
        }
        return "wechat-main".equalsIgnoreCase(providerKey) ? "wechat-main" : providerKey;
    }

    private int providerKeyRank(String providerType, String providerKey) {
        if (!"wechat".equals(providerType)) {
            return 0;
        }
        if ("wechat-main".equalsIgnoreCase(providerKey)) {
            return 0;
        }
        if ("wechat".equalsIgnoreCase(providerKey)) {
            return 2;
        }
        return 1;
    }

    private long requireGeneratedId(KeyHolder keyHolder, String message) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException(message);
        }
        return key.longValue();
    }

    private Map<String, Object> copyMap(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        return new LinkedHashMap<>(input);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record AuthIdentityRow(
            long id,
            long userId,
            String providerType,
            String providerKey,
            String providerSubject,
            String issuer,
            Map<String, Object> metadata,
            OffsetDateTime verifiedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    private record AuthIdentityChannelRow(
            long id,
            long identityId,
            String providerType,
            String providerKey,
            String channel,
            String channelAppId,
            String channelSubject,
            Map<String, Object> metadata,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long userId
    ) {
    }
}
