package org.apiprivaterouter.javabackend.admin.account.repository;

import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountAiCreditResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountAvailableModelResponse;
import org.apiprivaterouter.javabackend.admin.account.model.ScheduledTestPlanResponse;
import org.apiprivaterouter.javabackend.admin.account.model.ScheduledTestResultResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountUsageHistoryResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountUsageInfoResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountUsageProgressResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountUsageStatsResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountUsageSummaryResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AntigravityModelQuotaResponse;
import org.apiprivaterouter.javabackend.admin.account.model.SimpleGroupResponse;
import org.apiprivaterouter.javabackend.admin.account.model.SimpleProxyResponse;
import org.apiprivaterouter.javabackend.admin.account.model.TempUnschedulableStateResponse;
import org.apiprivaterouter.javabackend.admin.account.model.TempUnschedulableStatusResponse;
import org.apiprivaterouter.javabackend.admin.account.model.WindowStatsResponse;
import org.apiprivaterouter.javabackend.admin.dashboard.model.ModelStatResponse;
import org.apiprivaterouter.javabackend.admin.usage.model.AdminUsageStatsResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class AdminAccountRepository {

    public static final String PRIVACY_MODE_UNSET_FILTER = "__unset__";
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final ZoneId GEMINI_QUOTA_ZONE = ZoneId.of("America/Los_Angeles");
    private static final List<AccountAvailableModelResponse> DEFAULT_OPENAI_MODELS = List.of(
            new AccountAvailableModelResponse("gpt-5", "model", "gpt-5", ""),
            new AccountAvailableModelResponse("gpt-5.1", "model", "gpt-5.1", ""),
            new AccountAvailableModelResponse("gpt-5.3-codex", "model", "gpt-5.3-codex", ""),
            new AccountAvailableModelResponse("gpt-5.4", "model", "gpt-5.4", ""),
            new AccountAvailableModelResponse("gpt-5.4-mini", "model", "gpt-5.4-mini", "")
    );
    private static final List<AccountAvailableModelResponse> DEFAULT_GEMINI_MODELS = List.of(
            new AccountAvailableModelResponse("gemini-2.5-pro", "model", "gemini-2.5-pro", ""),
            new AccountAvailableModelResponse("gemini-2.5-flash", "model", "gemini-2.5-flash", ""),
            new AccountAvailableModelResponse("gemini-2.0-flash", "model", "gemini-2.0-flash", ""),
            new AccountAvailableModelResponse("gemini-2.0-flash-lite", "model", "gemini-2.0-flash-lite", "")
    );
    private static final List<AccountAvailableModelResponse> DEFAULT_ANTHROPIC_MODELS = List.of(
            new AccountAvailableModelResponse("claude-3-5-sonnet", "model", "claude-3-5-sonnet", ""),
            new AccountAvailableModelResponse("claude-3-7-sonnet", "model", "claude-3-7-sonnet", ""),
            new AccountAvailableModelResponse("claude-sonnet-4-5", "model", "claude-sonnet-4-5", ""),
            new AccountAvailableModelResponse("claude-opus-4-5-thinking", "model", "claude-opus-4-5-thinking", "")
    );
    private static final List<AccountAvailableModelResponse> DEFAULT_ANTIGRAVITY_MODELS = List.of(
            new AccountAvailableModelResponse("claude-sonnet-4-5", "model", "claude-sonnet-4-5", ""),
            new AccountAvailableModelResponse("claude-opus-4-5-thinking", "model", "claude-opus-4-5-thinking", ""),
            new AccountAvailableModelResponse("gemini-3-flash", "model", "gemini-3-flash", ""),
            new AccountAvailableModelResponse("gemini-3.1-pro-high", "model", "gemini-3.1-pro-high", ""),
            new AccountAvailableModelResponse("gemini-3.1-pro-low", "model", "gemini-3.1-pro-low", "")
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonHelper jsonHelper;

    public AdminAccountRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonHelper jsonHelper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonHelper = jsonHelper;
    }

    public PageResponse<AdminAccountResponse> listAccounts(
            int page,
            int pageSize,
            String platform,
            String type,
            String status,
            String group,
            String privacyMode,
            String search,
            String sortBy,
            String sortOrder
    ) {
        return listAccounts(page, pageSize, platform, type, status, group, privacyMode, search, sortBy, sortOrder, false);
    }

    public PageResponse<AdminAccountResponse> listAccounts(
            int page,
            int pageSize,
            String platform,
            String type,
            String status,
            String group,
            String privacyMode,
            String search,
            String sortBy,
            String sortOrder,
            boolean lite
    ) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = pageSize <= 0 ? 20 : Math.min(pageSize, 200);
        int offset = Math.max(normalizedPage - 1, 0) * normalizedPageSize;
        long nowEpoch = Instant.now().getEpochSecond();
        String normalizedSearch = normalizeSearch(search);
        boolean ungrouped = "ungrouped".equals(group);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pageSize", normalizedPageSize)
                .addValue("offset", offset)
                .addValue("nowEpoch", nowEpoch);
        applyAccountListFilters(params, platform, type, status, group, privacyMode, normalizedSearch, ungrouped);

        String where = baseAccountWhereClause(platform, type, status, group, privacyMode, normalizedSearch, ungrouped);
        Long total = jdbcTemplate.queryForObject("""
                select count(distinct a.id)
                from accounts a
                """ + where, params, Long.class);

        List<AccountRecord> records = jdbcTemplate.query("""
                select a.id, a.name, a.notes, a.platform, a.type, a.credentials::text as credentials_json,
                       a.extra::text as extra_json, a.proxy_id, a.concurrency, a.load_factor, a.priority,
                       a.rate_multiplier, a.status, a.error_message, a.last_used_at, a.expires_at,
                       a.auto_pause_on_expired, a.created_at, a.updated_at, a.schedulable, a.rate_limited_at,
                       a.rate_limit_reset_at, a.overload_until, a.temp_unschedulable_until,
                       a.temp_unschedulable_reason, a.session_window_start, a.session_window_end,
                       a.session_window_status,
                       p.id as proxy_ref_id, p.name as proxy_name, p.protocol as proxy_protocol, p.host as proxy_host,
                       p.port as proxy_port, p.username as proxy_username, p.status as proxy_status,
                       p.created_at as proxy_created_at, p.updated_at as proxy_updated_at
                from accounts a
                left join proxies p on p.id = a.proxy_id and p.deleted_at is null
                """ + where + buildOrderBy(sortBy, sortOrder) + """
                limit :pageSize offset :offset
                """, params, accountRecordRowMapper());

        Map<Long, List<GroupBindingRecord>> groupBindings = loadGroupBindings(extractIds(records));
        List<AdminAccountResponse> items = records.stream()
                .map(record -> toResponse(record, groupBindings.getOrDefault(record.id(), List.of()), lite))
                .toList();
        return new PageResponse<>(items, total == null ? 0 : total, normalizedPage, normalizedPageSize);
    }

    public String computeListEtag(
            PageResponse<AdminAccountResponse> page,
            String platform,
            String type,
            String status,
            String group,
            String privacyMode,
            String search,
            String sortBy,
            String sortOrder,
            boolean lite
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(page.total()).getBytes(StandardCharsets.UTF_8));
            digest.update((page.page() + ":" + page.page_size()).getBytes(StandardCharsets.UTF_8));
            digest.update(defaultString(platform).getBytes(StandardCharsets.UTF_8));
            digest.update(defaultString(type).getBytes(StandardCharsets.UTF_8));
            digest.update(defaultString(status).getBytes(StandardCharsets.UTF_8));
            digest.update(defaultString(group).getBytes(StandardCharsets.UTF_8));
            digest.update(defaultString(privacyMode).getBytes(StandardCharsets.UTF_8));
            digest.update(defaultString(search).getBytes(StandardCharsets.UTF_8));
            digest.update(defaultString(sortBy).getBytes(StandardCharsets.UTF_8));
            digest.update(defaultString(sortOrder).getBytes(StandardCharsets.UTF_8));
            digest.update(Boolean.toString(lite).getBytes(StandardCharsets.UTF_8));
            for (AdminAccountResponse item : page.items()) {
                digest.update(Long.toString(item.id()).getBytes(StandardCharsets.UTF_8));
                digest.update(defaultString(item.updated_at()).getBytes(StandardCharsets.UTF_8));
                digest.update(defaultString(item.status()).getBytes(StandardCharsets.UTF_8));
                digest.update(Boolean.toString(item.schedulable()).getBytes(StandardCharsets.UTF_8));
                digest.update(defaultString(item.rate_limit_reset_at()).getBytes(StandardCharsets.UTF_8));
                digest.update(defaultString(item.temp_unschedulable_until()).getBytes(StandardCharsets.UTF_8));
                digest.update(Integer.toString(item.current_concurrency() == null ? 0 : item.current_concurrency()).getBytes(StandardCharsets.UTF_8));
                digest.update(Integer.toString(item.active_sessions() == null ? 0 : item.active_sessions()).getBytes(StandardCharsets.UTF_8));
                digest.update(Integer.toString(item.current_rpm() == null ? 0 : item.current_rpm()).getBytes(StandardCharsets.UTF_8));
                digest.update(defaultString(item.name()).getBytes(StandardCharsets.UTF_8));
            }
            return "\"" + bytesToHex(digest.digest()) + "\"";
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("missing SHA-256", ex);
        }
    }

    public Optional<AdminAccountResponse> getAccount(long id) {
        List<AccountRecord> rows = jdbcTemplate.query("""
                select a.id, a.name, a.notes, a.platform, a.type, a.credentials::text as credentials_json,
                       a.extra::text as extra_json, a.proxy_id, a.concurrency, a.load_factor, a.priority,
                       a.rate_multiplier, a.status, a.error_message, a.last_used_at, a.expires_at,
                       a.auto_pause_on_expired, a.created_at, a.updated_at, a.schedulable, a.rate_limited_at,
                       a.rate_limit_reset_at, a.overload_until, a.temp_unschedulable_until,
                       a.temp_unschedulable_reason, a.session_window_start, a.session_window_end,
                       a.session_window_status,
                       p.id as proxy_ref_id, p.name as proxy_name, p.protocol as proxy_protocol, p.host as proxy_host,
                       p.port as proxy_port, p.username as proxy_username, p.status as proxy_status,
                       p.created_at as proxy_created_at, p.updated_at as proxy_updated_at
                from accounts a
                left join proxies p on p.id = a.proxy_id and p.deleted_at is null
                where a.id = :id and a.deleted_at is null
                """, new MapSqlParameterSource("id", id), accountRecordRowMapper());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, List<GroupBindingRecord>> groupBindings = loadGroupBindings(List.of(id));
        return Optional.of(toResponse(rows.get(0), groupBindings.getOrDefault(id, List.of()), false));
    }

    public List<AdminAccountResponse> getAccountsByIds(List<Long> accountIds) {
        List<Long> ids = normalizeIdList(accountIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<AccountRecord> rows = jdbcTemplate.query("""
                select a.id, a.name, a.notes, a.platform, a.type, a.credentials::text as credentials_json,
                       a.extra::text as extra_json, a.proxy_id, a.concurrency, a.load_factor, a.priority,
                       a.rate_multiplier, a.status, a.error_message, a.last_used_at, a.expires_at,
                       a.auto_pause_on_expired, a.created_at, a.updated_at, a.schedulable, a.rate_limited_at,
                       a.rate_limit_reset_at, a.overload_until, a.temp_unschedulable_until,
                       a.temp_unschedulable_reason, a.session_window_start, a.session_window_end,
                       a.session_window_status,
                       p.id as proxy_ref_id, p.name as proxy_name, p.protocol as proxy_protocol, p.host as proxy_host,
                       p.port as proxy_port, p.username as proxy_username, p.status as proxy_status,
                       p.created_at as proxy_created_at, p.updated_at as proxy_updated_at
                from accounts a
                left join proxies p on p.id = a.proxy_id and p.deleted_at is null
                where a.id in (:ids) and a.deleted_at is null
                order by a.id asc
                """, new MapSqlParameterSource("ids", ids), accountRecordRowMapper());
        Map<Long, List<GroupBindingRecord>> groupBindings = loadGroupBindings(extractIds(rows));
        return rows.stream()
                .map(record -> toResponse(record, groupBindings.getOrDefault(record.id(), List.of()), false))
                .toList();
    }

    public Optional<AdminAccountResponse> getAccountByCrsAccountId(String crsAccountId) {
        String normalized = blankToNull(crsAccountId);
        if (normalized == null) {
            return Optional.empty();
        }
        List<AccountRecord> rows = jdbcTemplate.query("""
                select a.id, a.name, a.notes, a.platform, a.type, a.credentials::text as credentials_json,
                       a.extra::text as extra_json, a.proxy_id, a.concurrency, a.load_factor, a.priority,
                       a.rate_multiplier, a.status, a.error_message, a.last_used_at, a.expires_at,
                       a.auto_pause_on_expired, a.created_at, a.updated_at, a.schedulable, a.rate_limited_at,
                       a.rate_limit_reset_at, a.overload_until, a.temp_unschedulable_until,
                       a.temp_unschedulable_reason, a.session_window_start, a.session_window_end,
                       a.session_window_status,
                       p.id as proxy_ref_id, p.name as proxy_name, p.protocol as proxy_protocol, p.host as proxy_host,
                       p.port as proxy_port, p.username as proxy_username, p.status as proxy_status,
                       p.created_at as proxy_created_at, p.updated_at as proxy_updated_at
                from accounts a
                left join proxies p on p.id = a.proxy_id and p.deleted_at is null
                where a.deleted_at is null
                  and coalesce(a.extra->>'crs_account_id', '') = :crsAccountId
                order by a.id asc
                limit 1
                """, new MapSqlParameterSource("crsAccountId", normalized), accountRecordRowMapper());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        long id = rows.get(0).id();
        Map<Long, List<GroupBindingRecord>> groupBindings = loadGroupBindings(List.of(id));
        return Optional.of(toResponse(rows.get(0), groupBindings.getOrDefault(id, List.of()), false));
    }

    public Set<String> listCrsAccountIds() {
        List<String> ids = jdbcTemplate.query("""
                select distinct a.extra->>'crs_account_id' as crs_account_id
                from accounts a
                where a.deleted_at is null
                  and a.extra ? 'crs_account_id'
                  and coalesce(a.extra->>'crs_account_id', '') <> ''
                """, new MapSqlParameterSource(), (rs, rowNum) -> defaultString(rs.getString("crs_account_id")));
        return new LinkedHashSet<>(ids);
    }

    public long createAccount(
            String name,
            String notes,
            String platform,
            String type,
            Map<String, Object> credentials,
            Map<String, Object> extra,
            Long proxyId,
            int concurrency,
            Integer loadFactor,
            int priority,
            Double rateMultiplier,
            String status,
            boolean schedulable,
            Long expiresAtEpoch,
            boolean autoPauseOnExpired
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                insert into accounts (
                    name, notes, platform, type, credentials, extra, proxy_id, concurrency, load_factor,
                    priority, rate_multiplier, status, error_message, schedulable, expires_at,
                    auto_pause_on_expired, created_at, updated_at
                ) values (
                    :name, :notes, :platform, :type, cast(:credentials as jsonb), cast(:extra as jsonb), :proxyId,
                    :concurrency, :loadFactor, :priority, :rateMultiplier, :status, '', :schedulable,
                    :expiresAt, :autoPauseOnExpired, now(), now()
                )
                returning id
                """, new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("notes", notes)
                .addValue("platform", platform)
                .addValue("type", type)
                .addValue("credentials", jsonHelper.writeJson(credentials))
                .addValue("extra", jsonHelper.writeJson(extra))
                .addValue("proxyId", proxyId)
                .addValue("concurrency", concurrency)
                .addValue("loadFactor", loadFactor)
                .addValue("priority", priority)
                .addValue("rateMultiplier", rateMultiplier == null ? 1.0 : rateMultiplier)
                .addValue("status", status)
                .addValue("schedulable", schedulable)
                .addValue("expiresAt", toTimestamp(expiresAtEpoch))
                .addValue("autoPauseOnExpired", autoPauseOnExpired), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to create account");
        }
        return key.longValue();
    }

    public int updateAccountColumns(
            long id,
            String name,
            boolean notesPresent,
            String notes,
            String type,
            boolean credentialsPresent,
            Map<String, Object> credentials,
            boolean extraPresent,
            Map<String, Object> extra,
            boolean proxyIdPresent,
            Long proxyId,
            Integer concurrency,
            Integer loadFactor,
            boolean loadFactorPresent,
            Integer priority,
            Double rateMultiplier,
            Boolean schedulable,
            String status,
            boolean expiresAtPresent,
            Long expiresAtEpoch,
            Boolean autoPauseOnExpired
    ) {
        StringBuilder sql = new StringBuilder("""
                update accounts
                set updated_at = now()
                """);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", id);
        if (name != null) {
            sql.append(", name = :name");
            params.addValue("name", name);
        }
        if (notesPresent) {
            sql.append(", notes = :notes");
            params.addValue("notes", notes);
        }
        if (type != null) {
            sql.append(", type = :type");
            params.addValue("type", type);
        }
        if (credentialsPresent) {
            sql.append(", credentials = cast(:credentials as jsonb)");
            params.addValue("credentials", jsonHelper.writeJson(credentials));
        }
        if (extraPresent) {
            sql.append(", extra = cast(:extra as jsonb)");
            params.addValue("extra", jsonHelper.writeJson(extra));
        }
        if (proxyIdPresent) {
            sql.append(", proxy_id = :proxyId");
            params.addValue("proxyId", proxyId);
        }
        if (concurrency != null) {
            sql.append(", concurrency = :concurrency");
            params.addValue("concurrency", concurrency);
        }
        if (loadFactorPresent) {
            sql.append(", load_factor = :loadFactor");
            params.addValue("loadFactor", loadFactor);
        }
        if (priority != null) {
            sql.append(", priority = :priority");
            params.addValue("priority", priority);
        }
        if (rateMultiplier != null) {
            sql.append(", rate_multiplier = :rateMultiplier");
            params.addValue("rateMultiplier", rateMultiplier);
        }
        if (schedulable != null) {
            sql.append(", schedulable = :schedulable");
            params.addValue("schedulable", schedulable);
        }
        if (status != null) {
            sql.append(", status = :status");
            params.addValue("status", status);
        }
        if (expiresAtPresent) {
            sql.append(", expires_at = :expiresAt");
            params.addValue("expiresAt", toTimestamp(expiresAtEpoch));
        }
        if (autoPauseOnExpired != null) {
            sql.append(", auto_pause_on_expired = :autoPauseOnExpired");
            params.addValue("autoPauseOnExpired", autoPauseOnExpired);
        }
        sql.append(" where id = :id and deleted_at is null");
        return jdbcTemplate.update(sql.toString(), params);
    }

    public int updateAccountForCrsSync(
            long id,
            String name,
            String platform,
            String type,
            Map<String, Object> credentials,
            Map<String, Object> extra,
            boolean proxyIdPresent,
            Long proxyId,
            int concurrency,
            int priority,
            String status,
            boolean schedulable
    ) {
        StringBuilder sql = new StringBuilder("""
                update accounts
                set updated_at = now(),
                    name = :name,
                    platform = :platform,
                    type = :type,
                    credentials = cast(:credentials as jsonb),
                    extra = cast(:extra as jsonb),
                    concurrency = :concurrency,
                    priority = :priority,
                    status = :status,
                    schedulable = :schedulable
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("platform", platform)
                .addValue("type", type)
                .addValue("credentials", jsonHelper.writeJson(credentials))
                .addValue("extra", jsonHelper.writeJson(extra))
                .addValue("concurrency", concurrency)
                .addValue("priority", priority)
                .addValue("status", status)
                .addValue("schedulable", schedulable);
        if (proxyIdPresent) {
            sql.append(", proxy_id = :proxyId");
            params.addValue("proxyId", proxyId);
        }
        sql.append(" where id = :id and deleted_at is null");
        return jdbcTemplate.update(sql.toString(), params);
    }

    public int bulkUpdateAccounts(
            List<Long> accountIds,
            String name,
            boolean proxyIdPresent,
            Long proxyId,
            Integer concurrency,
            Integer priority,
            Double rateMultiplier,
            boolean loadFactorPresent,
            Integer loadFactor,
            String status,
            Boolean schedulable,
            Map<String, Object> credentialsPatch,
            Map<String, Object> extraPatch
    ) {
        List<Long> ids = normalizeIdList(accountIds);
        if (ids.isEmpty()) {
            return 0;
        }
        boolean hasUpdates = name != null
                || proxyIdPresent
                || concurrency != null
                || priority != null
                || rateMultiplier != null
                || loadFactorPresent
                || status != null
                || schedulable != null
                || (credentialsPatch != null && !credentialsPatch.isEmpty())
                || (extraPatch != null && !extraPatch.isEmpty());
        if (!hasUpdates) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("""
                update accounts
                set updated_at = now()
                """);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("ids", ids);
        if (name != null) {
            sql.append(", name = :name");
            params.addValue("name", name);
        }
        if (proxyIdPresent) {
            sql.append(", proxy_id = :proxyId");
            params.addValue("proxyId", proxyId);
        }
        if (concurrency != null) {
            sql.append(", concurrency = :concurrency");
            params.addValue("concurrency", concurrency);
        }
        if (priority != null) {
            sql.append(", priority = :priority");
            params.addValue("priority", priority);
        }
        if (rateMultiplier != null) {
            sql.append(", rate_multiplier = :rateMultiplier");
            params.addValue("rateMultiplier", rateMultiplier);
        }
        if (loadFactorPresent) {
            sql.append(", load_factor = :loadFactor");
            params.addValue("loadFactor", loadFactor);
        }
        if (status != null) {
            sql.append(", status = :status");
            params.addValue("status", status);
        }
        if (schedulable != null) {
            sql.append(", schedulable = :schedulable");
            params.addValue("schedulable", schedulable);
        }
        if (credentialsPatch != null && !credentialsPatch.isEmpty()) {
            sql.append(", credentials = coalesce(credentials, '{}'::jsonb) || cast(:credentialsPatch as jsonb)");
            params.addValue("credentialsPatch", jsonHelper.writeJson(credentialsPatch));
        }
        if (extraPatch != null && !extraPatch.isEmpty()) {
            sql.append(", extra = coalesce(extra, '{}'::jsonb) || cast(:extraPatch as jsonb)");
            params.addValue("extraPatch", jsonHelper.writeJson(extraPatch));
        }
        sql.append(" where id in (:ids) and deleted_at is null");
        return jdbcTemplate.update(sql.toString(), params);
    }

    public int softDeleteAccount(long id) {
        return jdbcTemplate.update("""
                update accounts
                set deleted_at = now(), updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
    }

    public void deleteAccountGroups(long accountId) {
        jdbcTemplate.update("""
                delete from account_groups
                where account_id = :accountId
                """, new MapSqlParameterSource("accountId", accountId));
    }

    public void bindGroups(long accountId, List<Long> groupIds) {
        deleteAccountGroups(accountId);
        if (groupIds == null || groupIds.isEmpty()) {
            return;
        }
        int priority = 1;
        for (Long groupId : normalizeIdList(groupIds)) {
            jdbcTemplate.update("""
                    insert into account_groups (account_id, group_id, priority, created_at)
                    values (:accountId, :groupId, :priority, now())
                    on conflict do nothing
                    """, new MapSqlParameterSource()
                    .addValue("accountId", accountId)
                    .addValue("groupId", groupId)
                    .addValue("priority", priority));
            priority++;
        }
    }

    public void deleteScheduledTestPlans(long accountId) {
        jdbcTemplate.update("""
                delete from scheduled_test_plans
                where account_id = :accountId
                """, new MapSqlParameterSource("accountId", accountId));
    }

    public List<ScheduledTestPlanResponse> listScheduledTestPlansByAccount(long accountId) {
        return jdbcTemplate.query("""
                select id, account_id, model_id, cron_expression, enabled, max_results, auto_recover,
                       last_run_at, next_run_at, created_at, updated_at
                from scheduled_test_plans
                where account_id = :accountId
                order by created_at desc
                """, new MapSqlParameterSource("accountId", accountId), scheduledTestPlanRowMapper());
    }

    public Optional<ScheduledTestPlanResponse> getScheduledTestPlan(long id) {
        List<ScheduledTestPlanResponse> rows = jdbcTemplate.query("""
                select id, account_id, model_id, cron_expression, enabled, max_results, auto_recover,
                       last_run_at, next_run_at, created_at, updated_at
                from scheduled_test_plans
                where id = :id
                """, new MapSqlParameterSource("id", id), scheduledTestPlanRowMapper());
        return rows.stream().findFirst();
    }

    public ScheduledTestPlanResponse createScheduledTestPlan(
            long accountId,
            String modelId,
            String cronExpression,
            boolean enabled,
            int maxResults,
            boolean autoRecover,
            Instant nextRunAt
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("modelId", modelId)
                .addValue("cronExpression", cronExpression)
                .addValue("enabled", enabled)
                .addValue("maxResults", maxResults)
                .addValue("autoRecover", autoRecover)
                .addValue("nextRunAt", toTimestamp(nextRunAt));
        jdbcTemplate.update("""
                insert into scheduled_test_plans (
                    account_id, model_id, cron_expression, enabled, max_results, auto_recover, next_run_at, created_at, updated_at
                ) values (
                    :accountId, :modelId, :cronExpression, :enabled, :maxResults, :autoRecover, :nextRunAt, now(), now()
                )
                """, params, keyHolder, new String[]{"id"});
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("failed to create scheduled test plan");
        }
        return getScheduledTestPlan(id.longValue())
                .orElseThrow(() -> new IllegalStateException("scheduled test plan not found after create"));
    }

    public ScheduledTestPlanResponse updateScheduledTestPlan(
            long id,
            String modelId,
            String cronExpression,
            boolean enabled,
            int maxResults,
            boolean autoRecover,
            Instant nextRunAt
    ) {
        int updated = jdbcTemplate.update("""
                update scheduled_test_plans
                set model_id = :modelId,
                    cron_expression = :cronExpression,
                    enabled = :enabled,
                    max_results = :maxResults,
                    auto_recover = :autoRecover,
                    next_run_at = :nextRunAt,
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("modelId", modelId)
                .addValue("cronExpression", cronExpression)
                .addValue("enabled", enabled)
                .addValue("maxResults", maxResults)
                .addValue("autoRecover", autoRecover)
                .addValue("nextRunAt", toTimestamp(nextRunAt)));
        if (updated == 0) {
            throw new IllegalArgumentException("scheduled test plan not found");
        }
        return getScheduledTestPlan(id)
                .orElseThrow(() -> new IllegalStateException("scheduled test plan not found after update"));
    }

    public int deleteScheduledTestPlan(long id) {
        return jdbcTemplate.update("""
                delete from scheduled_test_plans
                where id = :id
                """, new MapSqlParameterSource("id", id));
    }

    public List<ScheduledTestPlanResponse> listDueScheduledTestPlans(Instant now) {
        return jdbcTemplate.query("""
                select id, account_id, model_id, cron_expression, enabled, max_results, auto_recover,
                       last_run_at, next_run_at, created_at, updated_at
                from scheduled_test_plans
                where enabled = true and next_run_at <= :now
                order by next_run_at asc, id asc
                """, new MapSqlParameterSource("now", toTimestamp(now)), scheduledTestPlanRowMapper());
    }

    public void updateScheduledTestPlanAfterRun(long id, Instant lastRunAt, Instant nextRunAt) {
        jdbcTemplate.update("""
                update scheduled_test_plans
                set last_run_at = :lastRunAt,
                    next_run_at = :nextRunAt,
                    updated_at = now()
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("lastRunAt", toTimestamp(lastRunAt))
                .addValue("nextRunAt", toTimestamp(nextRunAt)));
    }

    public ScheduledTestResultResponse createScheduledTestResult(
            long planId,
            String status,
            String responseText,
            String errorMessage,
            long latencyMs,
            Instant startedAt,
            Instant finishedAt
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("planId", planId)
                .addValue("status", defaultString(status))
                .addValue("responseText", defaultString(responseText))
                .addValue("errorMessage", defaultString(errorMessage))
                .addValue("latencyMs", latencyMs)
                .addValue("startedAt", toTimestamp(startedAt))
                .addValue("finishedAt", toTimestamp(finishedAt));
        jdbcTemplate.update("""
                insert into scheduled_test_results (
                    plan_id, status, response_text, error_message, latency_ms, started_at, finished_at, created_at
                ) values (
                    :planId, :status, :responseText, :errorMessage, :latencyMs, :startedAt, :finishedAt, now()
                )
                """, params, keyHolder, new String[]{"id"});
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("failed to create scheduled test result");
        }
        List<ScheduledTestResultResponse> rows = jdbcTemplate.query("""
                select id, plan_id, status, response_text, error_message, latency_ms, started_at, finished_at, created_at
                from scheduled_test_results
                where id = :id
                """, new MapSqlParameterSource("id", id.longValue()), scheduledTestResultRowMapper());
        return rows.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("scheduled test result not found after create"));
    }

    public List<ScheduledTestResultResponse> listScheduledTestResults(long planId, int limit) {
        return jdbcTemplate.query("""
                select id, plan_id, status, response_text, error_message, latency_ms, started_at, finished_at, created_at
                from scheduled_test_results
                where plan_id = :planId
                order by created_at desc
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("planId", planId)
                .addValue("limit", limit), scheduledTestResultRowMapper());
    }

    public void pruneScheduledTestResults(long planId, int keepCount) {
        jdbcTemplate.update("""
                delete from scheduled_test_results
                where id in (
                    select id from (
                        select id,
                               row_number() over (partition by plan_id order by created_at desc) as rn
                        from scheduled_test_results
                        where plan_id = :planId
                    ) ranked
                    where rn > :keepCount
                )
                """, new MapSqlParameterSource()
                .addValue("planId", planId)
                .addValue("keepCount", keepCount));
    }

    public int setSchedulable(long id, boolean schedulable) {
        return jdbcTemplate.update("""
                update accounts
                set schedulable = :schedulable, updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("schedulable", schedulable));
    }

    public int clearError(long id) {
        return jdbcTemplate.update("""
                update accounts
                set status = 'active',
                    error_message = '',
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
    }

    public int clearRateLimit(long id) {
        return jdbcTemplate.update("""
                update accounts
                set rate_limited_at = null,
                    rate_limit_reset_at = null,
                    overload_until = null,
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
    }

    public int setRateLimited(long id, Instant resetAt) {
        Instant normalizedResetAt = resetAt == null ? Instant.now().plusSeconds(30) : resetAt;
        return jdbcTemplate.update("""
                update accounts
                set rate_limited_at = now(),
                    rate_limit_reset_at = :resetAt,
                    updated_at = now()
                where id = :id
                  and deleted_at is null
                  and (rate_limit_reset_at is null or rate_limit_reset_at < :resetAt)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("resetAt", Timestamp.from(normalizedResetAt)));
    }

    public int setOverloaded(long id, Instant until) {
        Instant normalizedUntil = until == null ? Instant.now().plusSeconds(30) : until;
        return jdbcTemplate.update("""
                update accounts
                set overload_until = :until,
                    updated_at = now()
                where id = :id
                  and deleted_at is null
                  and (overload_until is null or overload_until < :until)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("until", Timestamp.from(normalizedUntil)));
    }

    public int setTempUnschedulable(long id, Instant until, String reason) {
        Instant normalizedUntil = until == null ? Instant.now().plusSeconds(30) : until;
        return jdbcTemplate.update("""
                update accounts
                set temp_unschedulable_until = :until,
                    temp_unschedulable_reason = :reason,
                    updated_at = now()
                where id = :id
                  and deleted_at is null
                  and (temp_unschedulable_until is null or temp_unschedulable_until < :until)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("until", Timestamp.from(normalizedUntil))
                .addValue("reason", blankToNull(reason)));
    }

    public int clearTempUnschedulable(long id) {
        return jdbcTemplate.update("""
                update accounts
                set temp_unschedulable_until = null,
                    temp_unschedulable_reason = null,
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
    }

    public int clearAntigravityQuotaScopes(long id) {
        return jdbcTemplate.update("""
                update accounts
                set extra = coalesce(extra, '{}'::jsonb) - 'antigravity_quota_scopes',
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
    }

    public int clearModelRateLimits(long id) {
        return jdbcTemplate.update("""
                update accounts
                set extra = coalesce(extra, '{}'::jsonb) - 'model_rate_limits',
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
    }

    public int resetQuotaUsage(long id) {
        return jdbcTemplate.update("""
                update accounts
                set extra = (
                    coalesce(extra, '{}'::jsonb)
                    || '{"quota_used": 0, "quota_daily_used": 0, "quota_weekly_used": 0}'::jsonb
                ) - 'quota_daily_start' - 'quota_weekly_start' - 'quota_daily_reset_at' - 'quota_weekly_reset_at',
                    updated_at = now()
                where id = :id and deleted_at is null
                """, new MapSqlParameterSource("id", id));
    }

    public TempUnschedulableStatusResponse buildTempUnschedulableStatus(String untilIso, String reasonRaw) {
        Long untilUnix = parseIsoToEpoch(untilIso);
        if (untilUnix == null || untilUnix <= Instant.now().getEpochSecond()) {
            return new TempUnschedulableStatusResponse(false, null);
        }
        TempUnschedStatePayload payload = jsonHelper.readObject(reasonRaw, TempUnschedStatePayload.class);
        if (payload != null) {
            return new TempUnschedulableStatusResponse(true, new TempUnschedulableStateResponse(
                    payload.until_unix == null || payload.until_unix <= 0 ? untilUnix : payload.until_unix,
                    payload.triggered_at_unix,
                    payload.status_code,
                    blankToNull(payload.matched_keyword),
                    payload.rule_index == null ? 0 : payload.rule_index,
                    blankToNull(payload.error_message)
            ));
        }
        return new TempUnschedulableStatusResponse(true, new TempUnschedulableStateResponse(
                untilUnix,
                null,
                null,
                null,
                0,
                blankToNull(reasonRaw)
        ));
    }

    public WindowStatsResponse getTodayStats(long accountId, Instant startTime) {
        return jdbcTemplate.queryForObject("""
                select
                    count(*) as requests,
                    coalesce(sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0) as tokens,
                    coalesce(sum(coalesce(account_stats_cost, total_cost) * coalesce(account_rate_multiplier, 1)), 0) as cost,
                    coalesce(sum(total_cost), 0) as standard_cost,
                    coalesce(sum(actual_cost), 0) as user_cost
                from usage_logs
                where account_id = :accountId
                  and created_at >= :startTime
                """, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("startTime", Timestamp.from(startTime)), (rs, rowNum) -> mapWindowStats(rs));
    }

    public Map<String, WindowStatsResponse> getTodayStatsBatch(List<Long> accountIds, Instant startTime) {
        List<Long> ids = normalizeIdList(accountIds);
        Map<String, WindowStatsResponse> result = new LinkedHashMap<>();
        for (Long id : ids) {
            result.put(Long.toString(id), new WindowStatsResponse(0, 0, 0, 0, 0));
        }
        if (ids.isEmpty()) {
            return result;
        }
        jdbcTemplate.query("""
                select
                    account_id,
                    count(*) as requests,
                    coalesce(sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0) as tokens,
                    coalesce(sum(coalesce(account_stats_cost, total_cost) * coalesce(account_rate_multiplier, 1)), 0) as cost,
                    coalesce(sum(total_cost), 0) as standard_cost,
                    coalesce(sum(actual_cost), 0) as user_cost
                from usage_logs
                where account_id in (:accountIds)
                  and created_at >= :startTime
                group by account_id
                """, new MapSqlParameterSource()
                .addValue("accountIds", ids)
                .addValue("startTime", Timestamp.from(startTime)), rs -> {
            result.put(Long.toString(rs.getLong("account_id")), mapWindowStats(rs));
        });
        return result;
    }

    public List<AccountAvailableModelResponse> getAvailableModels(long accountId) {
        AccountRecord record = findAccountRecord(accountId);
        if (record == null) {
            return List.of();
        }
        Map<String, Object> credentials = record.credentials();
        Map<String, Object> extra = record.extra();
        String platform = record.platform();
        String type = record.type();
        Map<String, String> modelMapping = extractStringMap(credentials.get("model_mapping"));

        if ("openai".equals(platform)) {
            if (Boolean.TRUE.equals(parseBoolean(extra.get("openai_passthrough")))
                    || Boolean.TRUE.equals(parseBoolean(extra.get("openai_oauth_passthrough")))
                    || modelMapping.isEmpty()) {
                return DEFAULT_OPENAI_MODELS;
            }
            return mapModelsFromMapping(modelMapping, DEFAULT_OPENAI_MODELS);
        }

        if ("gemini".equals(platform)) {
            if ("oauth".equals(type) || modelMapping.isEmpty()) {
                return DEFAULT_GEMINI_MODELS;
            }
            return mapModelsFromMapping(modelMapping, DEFAULT_GEMINI_MODELS);
        }

        if ("antigravity".equals(platform)) {
            if (modelMapping.isEmpty()) {
                return DEFAULT_ANTIGRAVITY_MODELS;
            }
            return mapModelsFromMapping(modelMapping, DEFAULT_ANTIGRAVITY_MODELS);
        }

        if ("oauth".equals(type) || "setup-token".equals(type) || modelMapping.isEmpty()) {
            return DEFAULT_ANTHROPIC_MODELS;
        }
        return mapModelsFromMapping(modelMapping, DEFAULT_ANTHROPIC_MODELS);
    }

    public AccountUsageStatsResponse getAccountUsageStats(long accountId, int days, ZoneId zoneId) {
        int normalizedDays = Math.max(1, Math.min(days, 90));
        ZoneId resolvedZone = zoneId == null ? ZoneId.systemDefault() : zoneId;
        ZonedDateTime zonedNow = ZonedDateTime.now(resolvedZone);
        Instant startTime = zonedNow.toLocalDate().minusDays(normalizedDays - 1L).atStartOfDay(zonedNow.getZone()).toInstant();
        Instant endTime = zonedNow.toLocalDate().plusDays(1L).atStartOfDay(zonedNow.getZone()).toInstant();

        List<AccountUsageHistoryResponse> history = jdbcTemplate.query("""
                select
                    to_char(created_at at time zone :zoneId, 'YYYY-MM-DD') as usage_date,
                    count(*) as requests,
                    coalesce(sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0) as tokens,
                    coalesce(sum(total_cost), 0) as cost,
                    coalesce(sum(coalesce(account_stats_cost, total_cost) * coalesce(account_rate_multiplier, 1)), 0) as actual_cost,
                    coalesce(sum(actual_cost), 0) as user_cost
                from usage_logs
                where account_id = :accountId
                  and created_at >= :startTime
                  and created_at < :endTime
                group by usage_date
                order by usage_date asc
                """, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("startTime", Timestamp.from(startTime))
                .addValue("endTime", Timestamp.from(endTime))
                .addValue("zoneId", resolvedZone.getId()), (rs, rowNum) -> {
            String date = rs.getString("usage_date");
            return new AccountUsageHistoryResponse(
                    date,
                    toLabel(date),
                    rs.getLong("requests"),
                    rs.getLong("tokens"),
                    rs.getDouble("cost"),
                    rs.getDouble("actual_cost"),
                    rs.getDouble("user_cost")
            );
        });

        AccountUsageSummaryResponse summary = buildAccountUsageSummary(accountId, history, normalizedDays, resolvedZone, startTime, endTime);
        List<ModelStatResponse> models = loadAccountModelStats(accountId, startTime, endTime);
        List<AdminUsageStatsResponse.EndpointStat> endpoints = loadAccountEndpointStats(accountId, startTime, endTime, "inbound_endpoint");
        List<AdminUsageStatsResponse.EndpointStat> upstreamEndpoints = loadAccountEndpointStats(accountId, startTime, endTime, "upstream_endpoint");
        return new AccountUsageStatsResponse(history, summary, models, endpoints, upstreamEndpoints);
    }

    public AccountUsageInfoResponse getUsageInfo(long accountId, String source) {
        AccountRecord record = findAccountRecord(accountId);
        if (record == null) {
            return null;
        }
        String normalizedSource = "passive".equalsIgnoreCase(source) ? "passive" : "active";
        Map<String, Object> extra = record.extra();
        Instant now = Instant.now();

        if ("openai".equals(record.platform()) && "oauth".equals(record.type())) {
            return buildOpenAIUsage(extra, accountId, now);
        }
        if ("gemini".equals(record.platform())) {
            return buildGeminiUsage(record, accountId, now);
        }
        if ("antigravity".equals(record.platform()) && "oauth".equals(record.type())) {
            return buildAntigravityUsage(record, now);
        }
        if ("anthropic".equals(record.platform()) || "claude".equals(record.platform())) {
            return buildAnthropicUsage(record, normalizedSource, now);
        }
        return new AccountUsageInfoResponse(
                normalizedSource,
                now.toString(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public Optional<Long> findDefaultGroupId(String platform) {
        List<Long> rows = jdbcTemplate.query("""
                select id
                from groups
                where deleted_at is null
                  and status = 'active'
                  and platform = :platform
                  and name = :name
                order by id asc
                limit 1
                """, new MapSqlParameterSource()
                .addValue("platform", platform)
                .addValue("name", platform + "-default"), (rs, rowNum) -> rs.getLong("id"));
        return rows.stream().findFirst();
    }

    public boolean proxyExists(long proxyId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(
                    select 1
                    from proxies
                    where id = :proxyId and deleted_at is null
                )
                """, new MapSqlParameterSource("proxyId", proxyId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public Set<Long> existingGroupIds(List<Long> groupIds) {
        List<Long> ids = normalizeIdList(groupIds);
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbcTemplate.query("""
                select id
                from groups
                where id in (:ids) and deleted_at is null
                """, new MapSqlParameterSource("ids", ids), (rs, rowNum) -> rs.getLong("id")));
    }

    public List<MixedChannelCandidate> listAccountsByGroup(long groupId) {
        return jdbcTemplate.query("""
                select a.id, a.platform, g.id as group_id, g.name as group_name
                from account_groups ag
                join accounts a on a.id = ag.account_id and a.deleted_at is null
                join groups g on g.id = ag.group_id and g.deleted_at is null
                where ag.group_id = :groupId
                order by ag.priority asc, a.id asc
                """, new MapSqlParameterSource("groupId", groupId), (rs, rowNum) -> new MixedChannelCandidate(
                rs.getLong("id"),
                defaultString(rs.getString("platform")),
                rs.getLong("group_id"),
                defaultString(rs.getString("group_name"))
        ));
    }

    public record MixedChannelCandidate(long accountId, String platform, long groupId, String groupName) {
    }

    private String baseAccountWhereClause(
            String platform,
            String type,
            String status,
            String group,
            String privacyMode,
            String normalizedSearch,
            boolean ungrouped
    ) {
        StringBuilder sql = new StringBuilder("""
                where a.deleted_at is null
                """);
        if (blankToNull(platform) != null) {
            sql.append("\n  and a.platform = :platform");
        }
        if (blankToNull(type) != null) {
            sql.append("\n  and a.type = :type");
        }
        appendAccountStatusFilter(sql, status);
        appendAccountGroupFilter(sql, group, ungrouped);
        appendAccountPrivacyFilter(sql, privacyMode);
        if (normalizedSearch != null) {
            sql.append("\n  and a.name ilike :likeSearch");
        }
        sql.append('\n');
        return sql.toString();
    }

    private String buildOrderBy(String sortBy, String sortOrder) {
        String field = switch (sortBy == null ? "" : sortBy.trim()) {
            case "name" -> "a.name";
            case "status" -> "a.status";
            case "schedulable" -> "a.schedulable";
            case "priority" -> "a.priority";
            case "rate_multiplier" -> "a.rate_multiplier";
            case "last_used_at" -> "a.last_used_at";
            case "expires_at" -> "a.expires_at";
            case "created_at" -> "a.created_at";
            case "id" -> "a.id";
            default -> "a.name";
        };
        String direction = "desc".equalsIgnoreCase(sortOrder) ? "desc" : "asc";
        return "\norder by " + field + " " + direction + ", a.id desc\n";
    }

    private RowMapper<AccountRecord> accountRecordRowMapper() {
        return (rs, rowNum) -> new AccountRecord(
                rs.getLong("id"),
                defaultString(rs.getString("name")),
                rs.getString("notes"),
                defaultString(rs.getString("platform")),
                defaultString(rs.getString("type")),
                normalizeJsonMap(rs.getString("credentials_json")),
                normalizeJsonMap(rs.getString("extra_json")),
                rs.getObject("proxy_id", Long.class),
                rs.getInt("concurrency"),
                rs.getObject("load_factor", Integer.class),
                rs.getInt("priority"),
                toNullableDouble(rs.getBigDecimal("rate_multiplier")),
                defaultString(rs.getString("status")),
                rs.getString("error_message"),
                toIsoString(rs.getTimestamp("last_used_at")),
                toEpochSeconds(rs.getTimestamp("expires_at")),
                rs.getBoolean("auto_pause_on_expired"),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at")),
                rs.getBoolean("schedulable"),
                toIsoString(rs.getTimestamp("rate_limited_at")),
                toIsoString(rs.getTimestamp("rate_limit_reset_at")),
                toIsoString(rs.getTimestamp("overload_until")),
                toIsoString(rs.getTimestamp("temp_unschedulable_until")),
                rs.getString("temp_unschedulable_reason"),
                toIsoString(rs.getTimestamp("session_window_start")),
                toIsoString(rs.getTimestamp("session_window_end")),
                rs.getString("session_window_status"),
                rs.getObject("proxy_ref_id", Long.class),
                rs.getString("proxy_name"),
                rs.getString("proxy_protocol"),
                rs.getString("proxy_host"),
                rs.getObject("proxy_port", Integer.class),
                rs.getString("proxy_username"),
                rs.getString("proxy_status"),
                toIsoString(rs.getTimestamp("proxy_created_at")),
                toIsoString(rs.getTimestamp("proxy_updated_at"))
        );
    }

    private RowMapper<ScheduledTestPlanResponse> scheduledTestPlanRowMapper() {
        return (rs, rowNum) -> new ScheduledTestPlanResponse(
                rs.getLong("id"),
                rs.getLong("account_id"),
                defaultString(rs.getString("model_id")),
                defaultString(rs.getString("cron_expression")),
                rs.getBoolean("enabled"),
                rs.getInt("max_results"),
                rs.getBoolean("auto_recover"),
                toIsoString(rs.getTimestamp("last_run_at")),
                toIsoString(rs.getTimestamp("next_run_at")),
                toIsoString(rs.getTimestamp("created_at")),
                toIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private RowMapper<ScheduledTestResultResponse> scheduledTestResultRowMapper() {
        return (rs, rowNum) -> new ScheduledTestResultResponse(
                rs.getLong("id"),
                rs.getLong("plan_id"),
                defaultString(rs.getString("status")),
                defaultString(rs.getString("response_text")),
                defaultString(rs.getString("error_message")),
                rs.getLong("latency_ms"),
                toIsoString(rs.getTimestamp("started_at")),
                toIsoString(rs.getTimestamp("finished_at")),
                toIsoString(rs.getTimestamp("created_at"))
        );
    }

    private Map<Long, List<GroupBindingRecord>> loadGroupBindings(List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<GroupBindingRecord>> byAccount = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select ag.account_id, ag.priority,
                       g.id as group_id, g.name, g.description, g.platform, g.rate_multiplier, g.rpm_limit,
                       g.is_exclusive, g.status, g.subscription_type, g.daily_limit_usd, g.weekly_limit_usd,
                       g.monthly_limit_usd, g.allow_image_generation, g.image_rate_independent,
                       g.image_rate_multiplier, g.image_price_1k, g.image_price_2k, g.image_price_4k,
                       g.claude_code_only, g.fallback_group_id, g.fallback_group_id_on_invalid_request,
                       g.allow_messages_dispatch, g.default_mapped_model, g.require_oauth_only,
                       g.require_privacy_set, g.created_at, g.updated_at
                from account_groups ag
                join groups g on g.id = ag.group_id and g.deleted_at is null
                where ag.account_id in (:accountIds)
                order by ag.account_id asc, ag.priority asc
                """, new MapSqlParameterSource("accountIds", accountIds), rs -> {
            long accountId = rs.getLong("account_id");
            byAccount.computeIfAbsent(accountId, ignored -> new ArrayList<>())
                    .add(new GroupBindingRecord(
                            rs.getLong("group_id"),
                            rs.getInt("priority"),
                            new SimpleGroupResponse(
                                    rs.getLong("group_id"),
                                    defaultString(rs.getString("name")),
                                    rs.getString("description"),
                                    defaultString(rs.getString("platform")),
                                    toDouble(rs.getBigDecimal("rate_multiplier"), 1.0d),
                                    defaultInt(rs.getObject("rpm_limit", Integer.class), 0),
                                    rs.getBoolean("is_exclusive"),
                                    normalizeGroupStatus(rs.getString("status")),
                                    defaultString(rs.getString("subscription_type")),
                                    toNullableDouble(rs.getBigDecimal("daily_limit_usd")),
                                    toNullableDouble(rs.getBigDecimal("weekly_limit_usd")),
                                    toNullableDouble(rs.getBigDecimal("monthly_limit_usd")),
                                    rs.getBoolean("allow_image_generation"),
                                    rs.getBoolean("image_rate_independent"),
                                    toDouble(rs.getBigDecimal("image_rate_multiplier"), 1.0d),
                                    toNullableDouble(rs.getBigDecimal("image_price_1k")),
                                    toNullableDouble(rs.getBigDecimal("image_price_2k")),
                                    toNullableDouble(rs.getBigDecimal("image_price_4k")),
                                    rs.getBoolean("claude_code_only"),
                                    rs.getObject("fallback_group_id", Long.class),
                                    rs.getObject("fallback_group_id_on_invalid_request", Long.class),
                                    rs.getBoolean("allow_messages_dispatch"),
                                    rs.getString("default_mapped_model"),
                                    rs.getBoolean("require_oauth_only"),
                                    rs.getBoolean("require_privacy_set"),
                                    toIsoString(rs.getTimestamp("created_at")),
                                    toIsoString(rs.getTimestamp("updated_at"))
                            )
                    ));
        });
        return byAccount;
    }

    private AdminAccountResponse toResponse(AccountRecord record, List<GroupBindingRecord> groupBindings, boolean lite) {
        Map<String, Object> extra = new LinkedHashMap<>(record.extra());
        List<Long> groupIds = groupBindings.stream()
                .sorted(Comparator.comparingInt(GroupBindingRecord::priority))
                .map(GroupBindingRecord::groupId)
                .toList();
        List<SimpleGroupResponse> groups = groupBindings.stream()
                .sorted(Comparator.comparingInt(GroupBindingRecord::priority))
                .map(GroupBindingRecord::group)
                .toList();

        SimpleProxyResponse proxy = null;
        if (record.proxyRefId() != null && record.proxyPort() != null) {
            proxy = new SimpleProxyResponse(
                    record.proxyRefId(),
                    defaultString(record.proxyName()),
                    defaultString(record.proxyProtocol()),
                    defaultString(record.proxyHost()),
                    record.proxyPort(),
                    record.proxyUsername(),
                    normalizeProxyStatus(record.proxyStatus()),
                    asString(extra.get("proxy_country_code")),
                    record.proxyCreatedAt(),
                    record.proxyUpdatedAt()
            );
        }

        return new AdminAccountResponse(
                record.id(),
                record.name(),
                record.notes(),
                record.platform(),
                record.type(),
                record.credentials(),
                extra,
                record.proxyId(),
                record.concurrency(),
                record.loadFactor(),
                0,
                record.priority(),
                defaultDouble(record.rateMultiplier(), 1.0),
                normalizeAccountStatus(record.status()),
                blankToNull(record.errorMessage()),
                record.lastUsedAt(),
                record.expiresAt(),
                record.autoPauseOnExpired(),
                record.createdAt(),
                record.updatedAt(),
                record.schedulable(),
                record.rateLimitedAt(),
                record.rateLimitResetAt(),
                record.overloadUntil(),
                record.tempUnschedulableUntil(),
                blankToNull(record.tempUnschedulableReason()),
                record.sessionWindowStart(),
                record.sessionWindowEnd(),
                blankToNull(record.sessionWindowStatus()),
                extractPositiveDouble(extra, "window_cost_limit"),
                extractPositiveDouble(extra, "window_cost_sticky_reserve"),
                extractPositiveInt(extra, "max_sessions"),
                extractPositiveInt(extra, "session_idle_timeout_minutes"),
                extractPositiveInt(extra, "base_rpm"),
                extractString(extra, "rpm_strategy"),
                extractNonNegativeInt(extra, "rpm_sticky_buffer"),
                extractString(extra, "user_msg_queue_mode"),
                extractBooleanOrNull(extra, "enable_tls_fingerprint"),
                extractPositiveLong(extra, "tls_fingerprint_profile_id"),
                extractBooleanOrNull(extra, "session_id_masking_enabled"),
                extractBooleanOrNull(extra, "cache_ttl_override_enabled"),
                extractString(extra, "cache_ttl_override_target"),
                extractBooleanOrNull(extra, "custom_base_url_enabled"),
                extractString(extra, "custom_base_url"),
                extractPositiveDouble(extra, "quota_limit"),
                resolveQuotaUsed(extra, "quota_limit", "quota_used"),
                extractPositiveDouble(extra, "quota_daily_limit"),
                resolveQuotaUsed(extra, "quota_daily_limit", "quota_daily_used"),
                extractPositiveDouble(extra, "quota_weekly_limit"),
                resolveQuotaUsed(extra, "quota_weekly_limit", "quota_weekly_used"),
                extractString(extra, "quota_daily_reset_mode"),
                extractNonNegativeInt(extra, "quota_daily_reset_hour"),
                extractString(extra, "quota_weekly_reset_mode"),
                extractBoundedInt(extra, "quota_weekly_reset_day", 0, 6),
                extractNonNegativeInt(extra, "quota_weekly_reset_hour"),
                extractString(extra, "quota_reset_timezone"),
                extractString(extra, "quota_daily_reset_at"),
                extractString(extra, "quota_weekly_reset_at"),
                extractBooleanOrNull(extra, "quota_notify_daily_enabled"),
                extractPositiveDouble(extra, "quota_notify_daily_threshold"),
                extractBooleanOrNull(extra, "quota_notify_weekly_enabled"),
                extractPositiveDouble(extra, "quota_notify_weekly_threshold"),
                extractBooleanOrNull(extra, "quota_notify_total_enabled"),
                extractPositiveDouble(extra, "quota_notify_total_threshold"),
                lite ? null : null,
                lite ? null : null,
                lite ? null : null,
                proxy,
                groupIds,
                groups
        );
    }

    private List<Long> extractIds(List<AccountRecord> records) {
        return records.stream().map(AccountRecord::id).toList();
    }

    private AccountRecord findAccountRecord(long accountId) {
        List<AccountRecord> rows = jdbcTemplate.query("""
                select a.id, a.name, a.notes, a.platform, a.type, a.credentials::text as credentials_json,
                       a.extra::text as extra_json, a.proxy_id, a.concurrency, a.load_factor, a.priority,
                       a.rate_multiplier, a.status, a.error_message, a.last_used_at, a.expires_at,
                       a.auto_pause_on_expired, a.created_at, a.updated_at, a.schedulable, a.rate_limited_at,
                       a.rate_limit_reset_at, a.overload_until, a.temp_unschedulable_until,
                       a.temp_unschedulable_reason, a.session_window_start, a.session_window_end,
                       a.session_window_status,
                       p.id as proxy_ref_id, p.name as proxy_name, p.protocol as proxy_protocol, p.host as proxy_host,
                       p.port as proxy_port, p.username as proxy_username, p.status as proxy_status,
                       p.created_at as proxy_created_at, p.updated_at as proxy_updated_at
                from accounts a
                left join proxies p on p.id = a.proxy_id and p.deleted_at is null
                where a.id = :id and a.deleted_at is null
                """, new MapSqlParameterSource("id", accountId), accountRecordRowMapper());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<AccountAvailableModelResponse> mapModelsFromMapping(
            Map<String, String> mapping,
            List<AccountAvailableModelResponse> defaults
    ) {
        if (mapping.isEmpty()) {
            return defaults;
        }
        Map<String, AccountAvailableModelResponse> byId = new LinkedHashMap<>();
        for (AccountAvailableModelResponse model : defaults) {
            byId.put(model.id(), model);
        }
        List<AccountAvailableModelResponse> result = new ArrayList<>();
        for (String requestedModel : mapping.keySet()) {
            AccountAvailableModelResponse existing = byId.get(requestedModel);
            if (existing != null) {
                result.add(existing);
            } else {
                result.add(new AccountAvailableModelResponse(requestedModel, "model", requestedModel, ""));
            }
        }
        return result;
    }

    private List<ModelStatResponse> loadAccountModelStats(long accountId, Instant startTime, Instant endTime) {
        return jdbcTemplate.query("""
                select
                    coalesce(nullif(trim(requested_model), ''), model) as model,
                    count(*) as requests,
                    coalesce(sum(input_tokens), 0) as input_tokens,
                    coalesce(sum(output_tokens), 0) as output_tokens,
                    coalesce(sum(cache_creation_tokens), 0) as cache_creation_tokens,
                    coalesce(sum(cache_read_tokens), 0) as cache_read_tokens,
                    coalesce(sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0) as total_tokens,
                    coalesce(sum(total_cost), 0) as cost,
                    coalesce(sum(coalesce(account_stats_cost, total_cost) * coalesce(account_rate_multiplier, 1)), 0) as actual_cost,
                    coalesce(sum(coalesce(account_stats_cost, total_cost) * coalesce(account_rate_multiplier, 1)), 0) as account_cost
                from usage_logs
                where account_id = :accountId
                  and created_at >= :startTime
                  and created_at < :endTime
                group by coalesce(nullif(trim(requested_model), ''), model)
                order by total_tokens desc, model asc
                """, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("startTime", Timestamp.from(startTime))
                .addValue("endTime", Timestamp.from(endTime)), (rs, rowNum) -> new ModelStatResponse(
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

    private List<AdminUsageStatsResponse.EndpointStat> loadAccountEndpointStats(
            long accountId,
            Instant startTime,
            Instant endTime,
            String endpointColumn
    ) {
        String sql = """
                select
                    coalesce(nullif(trim(%s), ''), 'unknown') as endpoint,
                    count(*) as requests,
                    coalesce(sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0) as total_tokens,
                    coalesce(sum(total_cost), 0) as cost,
                    coalesce(sum(coalesce(account_stats_cost, total_cost) * coalesce(account_rate_multiplier, 1)), 0) as actual_cost
                from usage_logs
                where account_id = :accountId
                  and created_at >= :startTime
                  and created_at < :endTime
                group by endpoint
                order by requests desc, endpoint asc
                """.formatted(endpointColumn);
        return jdbcTemplate.query(sql, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("startTime", Timestamp.from(startTime))
                .addValue("endTime", Timestamp.from(endTime)), (rs, rowNum) -> new AdminUsageStatsResponse.EndpointStat(
                rs.getString("endpoint"),
                rs.getLong("requests"),
                rs.getLong("total_tokens"),
                rs.getDouble("cost"),
                rs.getDouble("actual_cost")
        ));
    }

    private AccountUsageSummaryResponse buildAccountUsageSummary(
            long accountId,
            List<AccountUsageHistoryResponse> history,
            int days,
            ZoneId zoneId,
            Instant startTime,
            Instant endTime
    ) {
        double totalCost = 0;
        double totalUserCost = 0;
        double totalStandardCost = 0;
        long totalRequests = 0;
        long totalTokens = 0;
        AccountUsageHistoryResponse highestCost = null;
        AccountUsageHistoryResponse highestRequest = null;
        for (AccountUsageHistoryResponse item : history) {
            totalCost += item.actual_cost();
            totalUserCost += item.user_cost();
            totalStandardCost += item.cost();
            totalRequests += item.requests();
            totalTokens += item.tokens();
            if (highestCost == null || item.actual_cost() > highestCost.actual_cost()) {
                highestCost = item;
            }
            if (highestRequest == null || item.requests() > highestRequest.requests()) {
                highestRequest = item;
            }
        }

        int actualDaysUsed = history.isEmpty() ? 1 : history.size();
        String todayDate = LocalDate.now(zoneId).format(DateTimeFormatter.ISO_LOCAL_DATE);
        AccountUsageSummaryResponse.SummaryDay today = null;
        for (AccountUsageHistoryResponse item : history) {
            if (todayDate.equals(item.date())) {
                today = new AccountUsageSummaryResponse.SummaryDay(
                        item.date(),
                        item.actual_cost(),
                        item.user_cost(),
                        item.requests(),
                        item.tokens()
                );
                break;
            }
        }

        AccountUsageSummaryResponse.SummaryPeakDay highestCostDay = highestCost == null ? null
                : new AccountUsageSummaryResponse.SummaryPeakDay(
                highestCost.date(),
                highestCost.label(),
                highestCost.actual_cost(),
                highestCost.user_cost(),
                highestCost.requests()
        );
        AccountUsageSummaryResponse.SummaryPeakDay highestRequestDay = highestRequest == null ? null
                : new AccountUsageSummaryResponse.SummaryPeakDay(
                highestRequest.date(),
                highestRequest.label(),
                highestRequest.actual_cost(),
                highestRequest.user_cost(),
                highestRequest.requests()
        );

        Double avgDuration = jdbcTemplate.queryForObject("""
                select coalesce(avg(duration_ms), 0)
                from usage_logs
                where account_id = :accountId
                  and created_at >= :startTime
                  and created_at < :endTime
                """, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("startTime", Timestamp.from(startTime))
                .addValue("endTime", Timestamp.from(endTime)), Double.class);

        return new AccountUsageSummaryResponse(
                days,
                actualDaysUsed,
                totalCost,
                totalUserCost,
                totalStandardCost,
                totalRequests,
                totalTokens,
                totalCost / actualDaysUsed,
                totalUserCost / actualDaysUsed,
                (double) totalRequests / actualDaysUsed,
                (double) totalTokens / actualDaysUsed,
                avgDuration == null ? 0.0 : avgDuration,
                today,
                highestCostDay,
                highestRequestDay
        );
    }

    private AccountUsageInfoResponse buildAnthropicUsage(AccountRecord record, String source, Instant now) {
        Map<String, Object> extra = record.extra();
        AccountUsageProgressResponse fiveHour = buildAnthropicFiveHourProgress(record, now);
        AccountUsageProgressResponse sevenDay = null;
        String updatedAt = now.toString();

        if ("passive".equals(source)) {
            updatedAt = extractString(extra, "passive_usage_sampled_at");
            Double util7d = parseDouble(extra.get("passive_usage_7d_utilization"));
            Long resetUnix = parseLong(extra.get("passive_usage_7d_reset"));
            if ((util7d != null && util7d > 0) || (resetUnix != null && resetUnix > 0)) {
                Instant resetAt = resetUnix == null ? null : Instant.ofEpochSecond(resetUnix);
                sevenDay = new AccountUsageProgressResponse(
                        util7d == null ? 0.0 : util7d * 100,
                        resetAt == null ? null : resetAt.toString(),
                        remainingSeconds(now, resetAt),
                        null,
                        null,
                        null
                );
            }
        }

        if (updatedAt == null) {
            updatedAt = now.toString();
        }

        if ("active".equals(source) && record.type().equals("setup-token")) {
            source = "active";
        }

        if ("active".equals(source) && "oauth".equals(record.type())) {
            String passiveUpdated = extractString(extra, "passive_usage_sampled_at");
            if (passiveUpdated != null) {
                updatedAt = passiveUpdated;
            }
            if (sevenDay == null) {
                Double util7d = parseDouble(extra.get("passive_usage_7d_utilization"));
                Long resetUnix = parseLong(extra.get("passive_usage_7d_reset"));
                if ((util7d != null && util7d > 0) || (resetUnix != null && resetUnix > 0)) {
                    Instant resetAt = resetUnix == null ? null : Instant.ofEpochSecond(resetUnix);
                    sevenDay = new AccountUsageProgressResponse(
                            util7d == null ? 0.0 : util7d * 100,
                            resetAt == null ? null : resetAt.toString(),
                            remainingSeconds(now, resetAt),
                            null,
                            null,
                            null
                    );
                }
            }
        }

        return new AccountUsageInfoResponse(
                source,
                updatedAt,
                fiveHour,
                sevenDay,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private AccountUsageProgressResponse buildAnthropicFiveHourProgress(AccountRecord record, Instant now) {
        Map<String, Object> extra = record.extra();
        Instant resetAt = record.sessionWindowEnd() == null ? null : parseInstant(record.sessionWindowEnd());
        double utilization = 0.0;
        Double storedUtilization = parseDouble(extra.get("session_window_utilization"));
        if (storedUtilization != null) {
            utilization = storedUtilization * 100.0;
        } else if ("rejected".equals(record.sessionWindowStatus())) {
            utilization = 100.0;
        } else if ("allowed_warning".equals(record.sessionWindowStatus())) {
            utilization = 80.0;
        }
        return new AccountUsageProgressResponse(
                utilization,
                resetAt == null ? null : resetAt.toString(),
                remainingSeconds(now, resetAt),
                getWindowStats(record.id(), resolveAnthropicWindowStart(record, now)),
                null,
                null
        );
    }

    private Instant resolveAnthropicWindowStart(AccountRecord record, Instant now) {
        Instant start = parseInstant(record.sessionWindowStart());
        Instant end = parseInstant(record.sessionWindowEnd());
        if (start != null && end != null && end.isAfter(now)) {
            return start;
        }
        ZonedDateTime zonedNow = now.atZone(ZoneId.systemDefault());
        return zonedNow.withMinute(0).withSecond(0).withNano(0).toInstant();
    }

    private AccountUsageInfoResponse buildOpenAIUsage(Map<String, Object> extra, long accountId, Instant now) {
        AccountUsageProgressResponse fiveHour = buildCodexUsageProgress(extra, accountId, "5h", now);
        AccountUsageProgressResponse sevenDay = buildCodexUsageProgress(extra, accountId, "7d", now);
        String updatedAt = extractString(extra, "codex_usage_updated_at");
        return new AccountUsageInfoResponse(
                null,
                updatedAt == null ? now.toString() : updatedAt,
                fiveHour,
                sevenDay,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private AccountUsageProgressResponse buildCodexUsageProgress(
            Map<String, Object> extra,
            long accountId,
            String window,
            Instant now
    ) {
        String prefix = "5h".equals(window) ? "codex_5h_" : "codex_7d_";
        Double utilization = parseDouble(extra.get(prefix + "used_percent"));
        if (utilization == null) {
            return null;
        }
        Instant resetAt = parseInstant(extractString(extra, prefix + "reset_at"));
        if (resetAt == null) {
            Integer resetAfter = parseInt(extra.get(prefix + "reset_after_seconds"));
            if (resetAfter != null && resetAfter > 0) {
                Instant base = parseInstant(extractString(extra, "codex_usage_updated_at"));
                resetAt = (base == null ? now : base).plusSeconds(resetAfter);
            }
        }
        if (resetAt != null && !resetAt.isAfter(now)) {
            utilization = 0.0;
        }
        Instant windowStart = "5h".equals(window) ? now.minusSeconds(5L * 60 * 60) : now.minusSeconds(7L * 24 * 60 * 60);
        return new AccountUsageProgressResponse(
                utilization,
                resetAt == null ? null : resetAt.toString(),
                remainingSeconds(now, resetAt),
                getWindowStats(accountId, windowStart),
                null,
                null
        );
    }

    private AccountUsageInfoResponse buildGeminiUsage(AccountRecord record, long accountId, Instant now) {
        Map<String, Object> credentials = record.credentials();
        String tierId = extractString(credentials, "tier_id");
        String oauthType = extractString(credentials, "oauth_type");
        boolean codeAssist = "code_assist".equals(oauthType) || (oauthType == null && extractString(credentials, "project_id") != null);

        GeminiQuota quota = resolveGeminiQuota(tierId, oauthType, record.type(), codeAssist);
        if (!quota.hasAnyLimit()) {
            return new AccountUsageInfoResponse(
                    null,
                    now.toString(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        ZonedDateTime zonedNow = now.atZone(GEMINI_QUOTA_ZONE);
        Instant dayStart = zonedNow.toLocalDate().atStartOfDay(GEMINI_QUOTA_ZONE).toInstant();
        Instant minuteStart = now.atZone(ZoneOffset.UTC).withSecond(0).withNano(0).toInstant();
        Instant dailyResetAt = zonedNow.toLocalDate().plusDays(1).atStartOfDay(GEMINI_QUOTA_ZONE).toInstant();
        Instant minuteResetAt = minuteStart.plusSeconds(60);

        GeminiAggregateTotals dayTotals = loadGeminiAggregateTotals(accountId, dayStart, now);
        GeminiAggregateTotals minuteTotals = loadGeminiAggregateTotals(accountId, minuteStart, now);

        AccountUsageProgressResponse geminiSharedDaily = null;
        AccountUsageProgressResponse geminiProDaily = null;
        AccountUsageProgressResponse geminiFlashDaily = null;
        AccountUsageProgressResponse geminiSharedMinute = null;
        AccountUsageProgressResponse geminiProMinute = null;
        AccountUsageProgressResponse geminiFlashMinute = null;

        if (quota.sharedRpd > 0) {
            geminiSharedDaily = buildGeminiUsageProgress(
                    dayTotals.proRequests + dayTotals.flashRequests,
                    quota.sharedRpd,
                    dailyResetAt,
                    dayTotals.proTokens + dayTotals.flashTokens,
                    dayTotals.proCost + dayTotals.flashCost,
                    now
            );
        } else {
            geminiProDaily = buildGeminiUsageProgress(dayTotals.proRequests, quota.proRpd, dailyResetAt, dayTotals.proTokens, dayTotals.proCost, now);
            geminiFlashDaily = buildGeminiUsageProgress(dayTotals.flashRequests, quota.flashRpd, dailyResetAt, dayTotals.flashTokens, dayTotals.flashCost, now);
        }

        if (quota.sharedRpm > 0) {
            geminiSharedMinute = buildGeminiUsageProgress(
                    minuteTotals.proRequests + minuteTotals.flashRequests,
                    quota.sharedRpm,
                    minuteResetAt,
                    minuteTotals.proTokens + minuteTotals.flashTokens,
                    minuteTotals.proCost + minuteTotals.flashCost,
                    now
            );
        } else {
            geminiProMinute = buildGeminiUsageProgress(minuteTotals.proRequests, quota.proRpm, minuteResetAt, minuteTotals.proTokens, minuteTotals.proCost, now);
            geminiFlashMinute = buildGeminiUsageProgress(minuteTotals.flashRequests, quota.flashRpm, minuteResetAt, minuteTotals.flashTokens, minuteTotals.flashCost, now);
        }

        return new AccountUsageInfoResponse(
                null,
                now.toString(),
                null,
                null,
                null,
                geminiSharedDaily,
                geminiProDaily,
                geminiFlashDaily,
                geminiSharedMinute,
                geminiProMinute,
                geminiFlashMinute,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private AccountUsageProgressResponse buildGeminiUsageProgress(
            long used,
            long limit,
            Instant resetAt,
            long tokens,
            double cost,
            Instant now
    ) {
        if (limit <= 0) {
            return null;
        }
        return new AccountUsageProgressResponse(
                (double) used / (double) limit * 100.0,
                resetAt.toString(),
                remainingSeconds(now, resetAt),
                new WindowStatsResponse(used, tokens, cost, 0, 0),
                used,
                limit
        );
    }

    private AccountUsageInfoResponse buildAntigravityUsage(AccountRecord record, Instant now) {
        Map<String, Object> extra = record.extra();
        Map<String, Object> scopes = extractObjectMap(extra.get("antigravity_quota_scopes"));
        Map<String, AntigravityModelQuotaResponse> quotas = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : scopes.entrySet()) {
            Map<String, Object> scope = extractObjectMap(entry.getValue());
            Integer utilization = parseInt(scope.get("utilization"));
            String resetTime = extractString(scope, "reset_time");
            if (utilization != null || resetTime != null) {
                quotas.put(entry.getKey(), new AntigravityModelQuotaResponse(
                        utilization == null ? 0 : utilization,
                        resetTime
                ));
            }
        }
        List<AccountAiCreditResponse> credits = extractAiCredits(extra.get("ai_credits"));

        String errorMessage = blankToNull(record.errorMessage());
        String forbiddenType = classifyForbiddenType(errorMessage);
        boolean forbidden = forbiddenType != null;
        boolean needsReauth = !forbidden && errorMessage != null && errorMessage.toLowerCase(Locale.ROOT).contains("401");
        String errorCode = forbidden ? "forbidden" : needsReauth ? "unauthenticated" : null;
        return new AccountUsageInfoResponse(
                null,
                now.toString(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                quotas.isEmpty() ? null : quotas,
                credits.isEmpty() ? null : credits,
                forbidden ? Boolean.TRUE : null,
                forbidden ? errorMessage : null,
                forbiddenType,
                forbidden && "validation".equals(forbiddenType) ? extractValidationUrl(errorMessage) : null,
                forbidden && "validation".equals(forbiddenType) ? Boolean.TRUE : null,
                forbidden && "violation".equals(forbiddenType) ? Boolean.TRUE : null,
                needsReauth ? Boolean.TRUE : null,
                errorCode,
                (!forbidden && needsReauth) ? errorMessage : null
        );
    }

    private GeminiQuota resolveGeminiQuota(String tierId, String oauthType, String accountType, boolean codeAssist) {
        String tierKey = resolveGeminiTierKey(tierId, oauthType, accountType, codeAssist);
        if (tierKey == null) {
            return new GeminiQuota(0, 0, 0, 0, 0, 0);
        }
        return switch (tierKey) {
            case "aistudio_free" -> new GeminiQuota(0, 0, 50, 2, 1500, 15);
            case "aistudio_paid" -> new GeminiQuota(0, 0, -1, 1000, -1, 2000);
            case "google_one_free" -> new GeminiQuota(1000, 60, 0, 0, 0, 0);
            case "google_ai_pro", "gcp_standard" -> new GeminiQuota(1500, 120, 0, 0, 0, 0);
            case "google_ai_ultra", "gcp_enterprise" -> new GeminiQuota(2000, 120, 0, 0, 0, 0);
            default -> new GeminiQuota(0, 0, 0, 0, 0, 0);
        };
    }

    private String resolveGeminiTierKey(String tierId, String oauthType, String accountType, boolean codeAssist) {
        String normalizedOauthType = normalizeGeminiOauthType(oauthType, codeAssist);
        String canonicalTierId = canonicalGeminiTierIdForOauthType(normalizedOauthType, tierId);
        if (canonicalTierId != null && !"google_one_unknown".equals(canonicalTierId)) {
            return canonicalTierId;
        }
        return switch (normalizedOauthType) {
            case "google_one" -> "google_one_free";
            case "code_assist" -> "gcp_standard";
            case "ai_studio" -> "aistudio_free";
            default -> "apikey".equals(accountType) ? "aistudio_free" : null;
        };
    }

    private String normalizeGeminiOauthType(String oauthType, boolean codeAssist) {
        String normalized = blankToNull(oauthType);
        if (normalized == null) {
            return codeAssist ? "code_assist" : null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String canonicalGeminiTierIdForOauthType(String oauthType, String tierId) {
        String canonical = canonicalGeminiTierId(tierId);
        if (canonical == null) {
            return null;
        }
        if (oauthType == null) {
            return canonical;
        }
        return switch (oauthType) {
            case "google_one" -> Set.of("google_one_free", "google_ai_pro", "google_ai_ultra", "google_one_unknown").contains(canonical)
                    ? canonical : null;
            case "code_assist" -> Set.of("gcp_standard", "gcp_enterprise").contains(canonical)
                    ? canonical : null;
            case "ai_studio" -> Set.of("aistudio_free", "aistudio_paid").contains(canonical)
                    ? canonical : null;
            default -> canonical;
        };
    }

    private String canonicalGeminiTierId(String tierId) {
        String normalized = blankToNull(tierId);
        if (normalized == null) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "google_one_free", "google_ai_pro", "google_ai_ultra",
                    "gcp_standard", "gcp_enterprise", "aistudio_free",
                    "aistudio_paid", "google_one_unknown" -> lower;
            case "ai_premium" -> "google_ai_pro";
            case "google_one_unlimited" -> "google_ai_ultra";
            case "free", "google_one_basic", "google_one_standard" -> "google_one_free";
            case "standard", "pro", "legacy", "standard-tier", "pro-tier" -> "gcp_standard";
            case "enterprise", "ultra", "ultra-tier" -> "gcp_enterprise";
            default -> switch (normalized.toUpperCase(Locale.ROOT)) {
                case "AISTUDIO_FREE" -> "aistudio_free";
                case "AISTUDIO_PAID" -> "aistudio_paid";
                case "GOOGLE_ONE_FREE" -> "google_one_free";
                case "GOOGLE_AI_PRO" -> "google_ai_pro";
                case "GOOGLE_AI_ULTRA" -> "google_ai_ultra";
                case "GCP_STANDARD" -> "gcp_standard";
                case "GCP_ENTERPRISE" -> "gcp_enterprise";
                case "GOOGLE_ONE_UNKNOWN" -> "google_one_unknown";
                default -> null;
            };
        };
    }

    private GeminiAggregateTotals loadGeminiAggregateTotals(long accountId, Instant startTime, Instant endTime) {
        List<ModelStatResponse> stats = jdbcTemplate.query("""
                select
                    coalesce(nullif(trim(requested_model), ''), model) as model,
                    count(*) as requests,
                    coalesce(sum(input_tokens), 0) as input_tokens,
                    coalesce(sum(output_tokens), 0) as output_tokens,
                    coalesce(sum(cache_creation_tokens), 0) as cache_creation_tokens,
                    coalesce(sum(cache_read_tokens), 0) as cache_read_tokens,
                    coalesce(sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0) as total_tokens,
                    coalesce(sum(total_cost), 0) as cost,
                    coalesce(sum(coalesce(account_stats_cost, total_cost) * coalesce(account_rate_multiplier, 1)), 0) as actual_cost,
                    coalesce(sum(coalesce(account_stats_cost, total_cost) * coalesce(account_rate_multiplier, 1)), 0) as account_cost
                from usage_logs
                where account_id = :accountId
                  and created_at >= :startTime
                  and created_at < :endTime
                group by coalesce(nullif(trim(requested_model), ''), model)
                """, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("startTime", Timestamp.from(startTime))
                .addValue("endTime", Timestamp.from(endTime)), (rs, rowNum) -> new ModelStatResponse(
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
        GeminiAggregateTotals totals = new GeminiAggregateTotals();
        for (ModelStatResponse stat : stats) {
            String model = stat.model() == null ? "" : stat.model().toLowerCase(Locale.ROOT);
            boolean flash = model.contains("flash");
            if (flash) {
                totals.flashRequests += stat.requests();
                totals.flashTokens += stat.total_tokens();
                totals.flashCost += stat.actual_cost();
            } else {
                totals.proRequests += stat.requests();
                totals.proTokens += stat.total_tokens();
                totals.proCost += stat.actual_cost();
            }
        }
        return totals;
    }

    private WindowStatsResponse getWindowStats(long accountId, Instant startTime) {
        return jdbcTemplate.queryForObject("""
                select
                    count(*) as requests,
                    coalesce(sum(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0) as tokens,
                    coalesce(sum(coalesce(account_stats_cost, total_cost) * coalesce(account_rate_multiplier, 1)), 0) as cost,
                    coalesce(sum(total_cost), 0) as standard_cost,
                    coalesce(sum(actual_cost), 0) as user_cost
                from usage_logs
                where account_id = :accountId
                  and created_at >= :startTime
                """, new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("startTime", Timestamp.from(startTime)), (rs, rowNum) -> mapWindowStats(rs));
    }

    private String toLabel(String date) {
        try {
            LocalDate parsed = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
            return parsed.format(DateTimeFormatter.ofPattern("MM/dd"));
        } catch (Exception ex) {
            return date;
        }
    }

    private int remainingSeconds(Instant now, Instant resetAt) {
        if (resetAt == null) {
            return 0;
        }
        long remaining = resetAt.getEpochSecond() - now.getEpochSecond();
        return (int) Math.max(remaining, 0);
    }

    private Instant parseInstant(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, String> extractStringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey()).trim();
            String value = String.valueOf(entry.getValue()).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                result.put(key, value);
            }
        }
        return result;
    }

    private Map<String, Object> extractObjectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private List<AccountAiCreditResponse> extractAiCredits(Object raw) {
        if (!(raw instanceof Collection<?> list)) {
            return List.of();
        }
        List<AccountAiCreditResponse> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = extractObjectMap(item);
            if (map.isEmpty()) {
                continue;
            }
            result.add(new AccountAiCreditResponse(
                    extractString(map, "credit_type"),
                    parseDouble(map.get("amount")),
                    parseDouble(map.get("minimum_balance"))
            ));
        }
        return result;
    }

    private Boolean parseBoolean(Object raw) {
        if (raw instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) {
            return null;
        }
        return Boolean.parseBoolean(value);
    }

    private String classifyForbiddenType(String errorMessage) {
        if (!hasText(errorMessage)) {
            return null;
        }
        String normalized = errorMessage.toLowerCase(Locale.ROOT);
        if (normalized.contains("validation")) {
            return "validation";
        }
        if (normalized.contains("violation") || normalized.contains("banned")) {
            return "violation";
        }
        if (normalized.contains("403") || normalized.contains("forbidden")) {
            return "forbidden";
        }
        return null;
    }

    private String extractValidationUrl(String errorMessage) {
        if (!hasText(errorMessage)) {
            return null;
        }
        int start = errorMessage.indexOf("http://");
        if (start < 0) {
            start = errorMessage.indexOf("https://");
        }
        if (start < 0) {
            return null;
        }
        int end = errorMessage.indexOf(' ', start);
        return end < 0 ? errorMessage.substring(start).trim() : errorMessage.substring(start, end).trim();
    }

    private Map<String, Object> normalizeJsonMap(String raw) {
        Map<String, Object> parsed = jsonHelper.readObjectMap(raw);
        return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
    }

    private List<Long> normalizeIdList(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                unique.add(id);
            }
        }
        return List.copyOf(unique);
    }

    private String normalizeAccountStatus(String status) {
        String normalized = defaultString(status).toLowerCase(Locale.ROOT);
        if ("disabled".equals(normalized)) {
            return "inactive";
        }
        if ("error".equals(normalized)) {
            return "error";
        }
        return "active";
    }

    private String normalizeGroupStatus(String status) {
        return "disabled".equalsIgnoreCase(status) ? "inactive" : defaultString(status).isBlank() ? "active" : status.toLowerCase(Locale.ROOT);
    }

    private String normalizeProxyStatus(String status) {
        return "disabled".equalsIgnoreCase(status) ? "inactive" : defaultString(status).isBlank() ? "active" : status.toLowerCase(Locale.ROOT);
    }

    private String normalizeSearch(String search) {
        if (!hasText(search)) {
            return null;
        }
        String trimmed = search.trim();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    private Long parseGroupId(String group) {
        if (!hasText(group) || "ungrouped".equals(group.trim())) {
            return null;
        }
        try {
            return Long.parseLong(group.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Timestamp toTimestamp(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) {
            return null;
        }
        return Timestamp.from(Instant.ofEpochSecond(epochSeconds));
    }

    private String toIsoString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private Long toEpochSeconds(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().getEpochSecond();
    }

    private Long parseIsoToEpoch(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value.trim()).getEpochSecond();
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String stringValue = String.valueOf(value).trim();
        return stringValue.isEmpty() ? null : stringValue;
    }

    private Double defaultDouble(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private WindowStatsResponse mapWindowStats(ResultSet rs) throws SQLException {
        return new WindowStatsResponse(
                rs.getLong("requests"),
                rs.getLong("tokens"),
                toDouble(rs.getBigDecimal("cost"), 0.0d),
                toDouble(rs.getBigDecimal("standard_cost"), 0.0d),
                toDouble(rs.getBigDecimal("user_cost"), 0.0d)
        );
    }

    private void applyAccountListFilters(
            MapSqlParameterSource params,
            String platform,
            String type,
            String status,
            String group,
            String privacyMode,
            String normalizedSearch,
            boolean ungrouped
    ) {
        String normalizedPlatform = blankToNull(platform);
        if (normalizedPlatform != null) {
            params.addValue("platform", normalizedPlatform);
        }
        String normalizedType = blankToNull(type);
        if (normalizedType != null) {
            params.addValue("type", normalizedType);
        }
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null) {
            params.addValue("status", normalizedStatus);
        }
        Long groupId = parseGroupId(group);
        if (groupId != null) {
            params.addValue("groupId", groupId);
        }
        if (ungrouped) {
            params.addValue("groupUngrouped", true);
        }
        String normalizedPrivacyMode = blankToNull(privacyMode);
        if (normalizedPrivacyMode != null) {
            params.addValue("privacyMode", normalizedPrivacyMode);
            params.addValue("privacyUnsetFilter", PRIVACY_MODE_UNSET_FILTER);
        }
        if (normalizedSearch != null) {
            params.addValue("likeSearch", "%" + normalizedSearch + "%");
        }
    }

    private void appendAccountStatusFilter(StringBuilder sql, String status) {
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus == null) {
            return;
        }
        switch (normalizedStatus) {
            case "active" -> sql.append("""
                    
                      and a.status = 'active'
                      and a.schedulable = true
                      and (a.rate_limit_reset_at is null or extract(epoch from a.rate_limit_reset_at) <= :nowEpoch)
                      and (a.temp_unschedulable_until is null or extract(epoch from a.temp_unschedulable_until) <= :nowEpoch)
                    """);
            case "rate_limited" -> sql.append("""
                    
                      and a.status = 'active'
                      and a.rate_limit_reset_at is not null
                      and extract(epoch from a.rate_limit_reset_at) > :nowEpoch
                      and (a.temp_unschedulable_until is null or extract(epoch from a.temp_unschedulable_until) <= :nowEpoch)
                    """);
            case "temp_unschedulable" -> sql.append("""
                    
                      and a.status = 'active'
                      and a.temp_unschedulable_until is not null
                      and extract(epoch from a.temp_unschedulable_until) > :nowEpoch
                    """);
            case "unschedulable" -> sql.append("""
                    
                      and a.status = 'active'
                      and a.schedulable = false
                      and (a.rate_limit_reset_at is null or extract(epoch from a.rate_limit_reset_at) <= :nowEpoch)
                      and (a.temp_unschedulable_until is null or extract(epoch from a.temp_unschedulable_until) <= :nowEpoch)
                    """);
            case "inactive" -> sql.append("\n  and a.status = 'disabled'");
            default -> sql.append("\n  and a.status = :status");
        }
    }

    private void appendAccountGroupFilter(StringBuilder sql, String group, boolean ungrouped) {
        Long groupId = parseGroupId(group);
        if (ungrouped) {
            sql.append("""
                    
                      and not exists (
                          select 1
                          from account_groups ag0
                          where ag0.account_id = a.id
                      )
                    """);
            return;
        }
        if (groupId == null) {
            return;
        }
        sql.append("""
                
                  and exists (
                      select 1
                      from account_groups ag1
                      where ag1.account_id = a.id
                        and ag1.group_id = :groupId
                  )
                """);
    }

    private void appendAccountPrivacyFilter(StringBuilder sql, String privacyMode) {
        String normalizedPrivacyMode = blankToNull(privacyMode);
        if (normalizedPrivacyMode == null) {
            return;
        }
        if (PRIVACY_MODE_UNSET_FILTER.equals(normalizedPrivacyMode)) {
            sql.append("\n  and coalesce(trim(a.extra->>'privacy_mode'), '') = ''");
            return;
        }
        sql.append("\n  and a.extra->>'privacy_mode' = :privacyMode");
    }

    private Double extractPositiveDouble(Map<String, Object> extra, String key) {
        Double value = parseDouble(extra.get(key));
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private Double resolveQuotaUsed(Map<String, Object> extra, String limitKey, String usedKey) {
        Double limit = extractPositiveDouble(extra, limitKey);
        if (limit == null) {
            return null;
        }
        Double used = parseDouble(extra.get(usedKey));
        return used == null ? 0.0 : used;
    }

    private Integer extractPositiveInt(Map<String, Object> extra, String key) {
        Integer value = parseInt(extra.get(key));
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private Integer extractNonNegativeInt(Map<String, Object> extra, String key) {
        Integer value = parseInt(extra.get(key));
        if (value == null || value < 0) {
            return null;
        }
        return value;
    }

    private Integer extractBoundedInt(Map<String, Object> extra, String key, int min, int max) {
        Integer value = parseInt(extra.get(key));
        if (value == null || value < min || value > max) {
            return null;
        }
        return value;
    }

    private Long extractPositiveLong(Map<String, Object> extra, String key) {
        Long value = parseLong(extra.get(key));
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private Boolean extractBooleanOrNull(Map<String, Object> extra, String key) {
        Object value = extra.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        return Boolean.parseBoolean(raw);
    }

    private String extractString(Map<String, Object> extra, String key) {
        return asString(extra.get(key));
    }

    private Double parseDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Double toNullableDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private double toDouble(BigDecimal value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private Integer parseInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public void validateQuotaResetConfig(Map<String, Object> extra) {
        if (extra == null) {
            return;
        }
        String timezone = extractString(extra, "quota_reset_timezone");
        if (timezone != null) {
            try {
                ZoneId.of(timezone);
            } catch (DateTimeException ex) {
                throw new IllegalArgumentException("invalid quota_reset_timezone: must be a valid IANA timezone name");
            }
        }
        String dailyMode = extractString(extra, "quota_daily_reset_mode");
        if (dailyMode != null && !Set.of("rolling", "fixed").contains(dailyMode)) {
            throw new IllegalArgumentException("quota_daily_reset_mode must be 'rolling' or 'fixed'");
        }
        Integer dailyHour = parseInt(extra.get("quota_daily_reset_hour"));
        if (dailyHour != null && (dailyHour < 0 || dailyHour > 23)) {
            throw new IllegalArgumentException("quota_daily_reset_hour must be between 0 and 23");
        }
        String weeklyMode = extractString(extra, "quota_weekly_reset_mode");
        if (weeklyMode != null && !Set.of("rolling", "fixed").contains(weeklyMode)) {
            throw new IllegalArgumentException("quota_weekly_reset_mode must be 'rolling' or 'fixed'");
        }
        Integer weeklyDay = parseInt(extra.get("quota_weekly_reset_day"));
        if (weeklyDay != null && (weeklyDay < 0 || weeklyDay > 6)) {
            throw new IllegalArgumentException("quota_weekly_reset_day must be between 0 (Sunday) and 6 (Saturday)");
        }
        Integer weeklyHour = parseInt(extra.get("quota_weekly_reset_hour"));
        if (weeklyHour != null && (weeklyHour < 0 || weeklyHour > 23)) {
            throw new IllegalArgumentException("quota_weekly_reset_hour must be between 0 and 23");
        }
    }

    public void computeQuotaResetAt(Map<String, Object> extra) {
        if (extra == null) {
            return;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(extractString(extra, "quota_reset_timezone") == null ? "UTC" : extractString(extra, "quota_reset_timezone"));
        } catch (DateTimeException ex) {
            zone = ZoneId.of("UTC");
        }
        ZonedDateTime now = ZonedDateTime.now(zone);

        if ("fixed".equals(extractString(extra, "quota_daily_reset_mode"))) {
            int hour = extractBoundedInt(extra, "quota_daily_reset_hour", 0, 23) == null ? 0 : extractBoundedInt(extra, "quota_daily_reset_hour", 0, 23);
            ZonedDateTime resetAt = nextFixedDailyReset(hour, now);
            extra.put("quota_daily_reset_at", resetAt.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } else {
            extra.remove("quota_daily_reset_at");
        }

        if ("fixed".equals(extractString(extra, "quota_weekly_reset_mode"))) {
            int day = extractBoundedInt(extra, "quota_weekly_reset_day", 0, 6) == null ? 1 : extractBoundedInt(extra, "quota_weekly_reset_day", 0, 6);
            int hour = extractBoundedInt(extra, "quota_weekly_reset_hour", 0, 23) == null ? 0 : extractBoundedInt(extra, "quota_weekly_reset_hour", 0, 23);
            ZonedDateTime resetAt = nextFixedWeeklyReset(day, hour, now);
            extra.put("quota_weekly_reset_at", resetAt.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } else {
            extra.remove("quota_weekly_reset_at");
        }
    }

    private ZonedDateTime nextFixedDailyReset(int hour, ZonedDateTime now) {
        ZonedDateTime candidate = now.toLocalDate().atTime(hour, 0).atZone(now.getZone());
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private ZonedDateTime nextFixedWeeklyReset(int day, int hour, ZonedDateTime now) {
        int target = day == 0 ? 7 : day;
        int current = now.getDayOfWeek().getValue();
        int plusDays = (target - current + 7) % 7;
        ZonedDateTime candidate = now.toLocalDate().plusDays(plusDays).atTime(hour, 0).atZone(now.getZone());
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate;
    }

    private static final class GeminiQuota {
        private final long sharedRpd;
        private final long sharedRpm;
        private final long proRpd;
        private final long proRpm;
        private final long flashRpd;
        private final long flashRpm;

        private GeminiQuota(long sharedRpd, long sharedRpm, long proRpd, long proRpm, long flashRpd, long flashRpm) {
            this.sharedRpd = sharedRpd;
            this.sharedRpm = sharedRpm;
            this.proRpd = proRpd;
            this.proRpm = proRpm;
            this.flashRpd = flashRpd;
            this.flashRpm = flashRpm;
        }

        private boolean hasAnyLimit() {
            return sharedRpd != 0
                    || sharedRpm != 0
                    || proRpd != 0
                    || proRpm != 0
                    || flashRpd != 0
                    || flashRpm != 0;
        }
    }

    private static final class GeminiAggregateTotals {
        private long proRequests;
        private long flashRequests;
        private long proTokens;
        private long flashTokens;
        private double proCost;
        private double flashCost;
    }

    private record GroupBindingRecord(long groupId, int priority, SimpleGroupResponse group) {
    }

    private record AccountRecord(
            long id,
            String name,
            String notes,
            String platform,
            String type,
            Map<String, Object> credentials,
            Map<String, Object> extra,
            Long proxyId,
            int concurrency,
            Integer loadFactor,
            int priority,
            Double rateMultiplier,
            String status,
            String errorMessage,
            String lastUsedAt,
            Long expiresAt,
            boolean autoPauseOnExpired,
            String createdAt,
            String updatedAt,
            boolean schedulable,
            String rateLimitedAt,
            String rateLimitResetAt,
            String overloadUntil,
            String tempUnschedulableUntil,
            String tempUnschedulableReason,
            String sessionWindowStart,
            String sessionWindowEnd,
            String sessionWindowStatus,
            Long proxyRefId,
            String proxyName,
            String proxyProtocol,
            String proxyHost,
            Integer proxyPort,
            String proxyUsername,
            String proxyStatus,
            String proxyCreatedAt,
            String proxyUpdatedAt
    ) {
    }

    private static final class TempUnschedStatePayload {
        public Long until_unix;
        public Long triggered_at_unix;
        public Integer status_code;
        public String matched_keyword;
        public Integer rule_index;
        public String error_message;
    }
}
