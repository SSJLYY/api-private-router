package org.apiprivaterouter.javabackend.admin.account.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountDataImportRequest;
import org.apiprivaterouter.javabackend.admin.account.model.AdminDataAccount;
import org.apiprivaterouter.javabackend.admin.account.model.AdminDataImportError;
import org.apiprivaterouter.javabackend.admin.account.model.AdminDataImportResult;
import org.apiprivaterouter.javabackend.admin.account.model.AdminDataPayload;
import org.apiprivaterouter.javabackend.admin.account.model.AdminDataProxy;
import org.apiprivaterouter.javabackend.admin.account.model.AccountRefreshWarningResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountAvailableModelResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountUsageInfoResponse;
import org.apiprivaterouter.javabackend.admin.account.model.AccountUsageStatsResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BatchCreateAccountResultResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BatchCreateAccountsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BatchCreateAccountsResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BatchClearErrorRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BatchRefreshRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BatchRefreshResultResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BatchRefreshWarningResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BatchOperationErrorResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BatchOperationResultResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BatchRefreshTierRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BatchTodayStatsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BatchTodayStatsResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BatchUpdateCredentialsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.CheckMixedChannelRequest;
import org.apiprivaterouter.javabackend.admin.account.model.CheckMixedChannelResponse;
import org.apiprivaterouter.javabackend.admin.account.model.ClaudeOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.account.model.PreviewFromCrsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.PreviewFromCrsResult;
import org.apiprivaterouter.javabackend.admin.account.model.CreateAccountRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BulkUpdateAccountFiltersRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BulkUpdateAccountResultResponse;
import org.apiprivaterouter.javabackend.admin.account.model.BulkUpdateAccountsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.BulkUpdateAccountsResponse;
import org.apiprivaterouter.javabackend.admin.account.model.MixedChannelWarningDetailsResponse;
import org.apiprivaterouter.javabackend.admin.account.model.RefreshTierResponse;
import org.apiprivaterouter.javabackend.admin.account.model.SetSchedulableRequest;
import org.apiprivaterouter.javabackend.admin.account.model.SimpleProxyResponse;
import org.apiprivaterouter.javabackend.admin.account.model.SyncFromCrsRequest;
import org.apiprivaterouter.javabackend.admin.account.model.SyncFromCrsResult;
import org.apiprivaterouter.javabackend.admin.account.model.TempUnschedulableStatusResponse;
import org.apiprivaterouter.javabackend.admin.account.model.UpdateAccountRequest;
import org.apiprivaterouter.javabackend.admin.account.model.WindowStatsResponse;
import org.apiprivaterouter.javabackend.admin.antigravity.model.AntigravityOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.antigravity.service.AntigravityOAuthService;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.gemini.service.GeminiOAuthGatewayService;
import org.apiprivaterouter.javabackend.admin.openai.model.OpenAiOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.openai.service.OpenAiOAuthService;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository.MixedChannelCandidate;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.UpstreamUrlGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

@Service
public class AdminAccountService {

    private static final Set<String> SENSITIVE_CREDENTIAL_KEYS = Set.of(
            "access_token", "refresh_token", "password", "secret", "api_key",
            "setup_token", "token", "auth_token", "private_key", "client_secret",
            "id_token", "proxy_password"
    );

    private static final String DATA_TYPE = "api-private-router-data";
    private static final String LEGACY_DATA_TYPE = "api-private-router-bundle";
    private static final int DATA_VERSION = 1;
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_DISABLED = "disabled";
    private static final String STATUS_ERROR = "error";
    private static final String TYPE_OAUTH = "oauth";
    private static final String TYPE_SETUP_TOKEN = "setup-token";
    private static final String TYPE_APIKEY = "apikey";
    private static final String TYPE_UPSTREAM = "upstream";
    private static final String TYPE_BEDROCK = "bedrock";
    private static final String TYPE_SERVICE_ACCOUNT = "service_account";
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            TYPE_OAUTH, TYPE_SETUP_TOKEN, TYPE_APIKEY, TYPE_UPSTREAM, TYPE_BEDROCK, TYPE_SERVICE_ACCOUNT
    );
    private static final Set<String> MIXED_CHANNEL_PLATFORMS = Set.of("antigravity", "anthropic", "claude");
    private static final Map<String, String> DEFAULT_ANTIGRAVITY_MODEL_MAPPING = buildDefaultAntigravityModelMapping();

    private final AdminAccountRepository repository;
    private final AdminProxyRepository proxyRepository;
    private final GeminiTierRefreshService geminiTierRefreshService;
    private final CrsSyncService crsSyncService;
    private final AccountPrivacyService accountPrivacyService;
    private final OpenAiOAuthService openAiOAuthService;
    private final GeminiOAuthGatewayService geminiOAuthGatewayService;
    private final AntigravityOAuthService antigravityOAuthService;
    private final ClaudeOAuthService claudeOAuthService;
    private final ObjectMapper objectMapper;
    private final UpstreamUrlGuard upstreamUrlGuard;

    public AdminAccountService(
            AdminAccountRepository repository,
            AdminProxyRepository proxyRepository,
            GeminiTierRefreshService geminiTierRefreshService,
            CrsSyncService crsSyncService,
            AccountPrivacyService accountPrivacyService,
            OpenAiOAuthService openAiOAuthService,
            GeminiOAuthGatewayService geminiOAuthGatewayService,
            AntigravityOAuthService antigravityOAuthService,
            ClaudeOAuthService claudeOAuthService,
            ObjectMapper objectMapper,
            UpstreamUrlGuard upstreamUrlGuard
    ) {
        this.repository = repository;
        this.proxyRepository = proxyRepository;
        this.geminiTierRefreshService = geminiTierRefreshService;
        this.crsSyncService = crsSyncService;
        this.accountPrivacyService = accountPrivacyService;
        this.openAiOAuthService = openAiOAuthService;
        this.geminiOAuthGatewayService = geminiOAuthGatewayService;
        this.antigravityOAuthService = antigravityOAuthService;
        this.claudeOAuthService = claudeOAuthService;
        this.objectMapper = objectMapper;
        this.upstreamUrlGuard = upstreamUrlGuard;
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
        return repository.listAccounts(
                Math.max(page, 1),
                pageSize <= 0 ? 20 : Math.min(pageSize, 200),
                normalizePlatform(platform, true),
                normalizeType(type, true),
                normalizeFilterStatus(status),
                normalizeGroupFilter(group),
                normalizePrivacyMode(privacyMode),
                normalizeSearch(search),
                sortBy,
                sortOrder,
                lite
        );
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
        return repository.computeListEtag(page, platform, type, status, group, privacyMode, search, sortBy, sortOrder, lite);
    }

    public AdminAccountResponse getAccount(long id) {
        return repository.getAccount(id).orElseThrow(() -> new HttpStatusException(404, "account not found"));
    }

    public List<AccountAvailableModelResponse> getAvailableModels(long id) {
        getAccount(id);
        return repository.getAvailableModels(id);
    }

    public AccountUsageStatsResponse getAccountUsageStats(long id, Integer days, String timezone) {
        getAccount(id);
        return repository.getAccountUsageStats(id, normalizeStatsDays(days), resolveZoneId(timezone));
    }

    public AccountUsageInfoResponse getUsageInfo(long id, String source) {
        getAccount(id);
        AccountUsageInfoResponse usage = repository.getUsageInfo(id, normalizeUsageSource(source));
        if (usage == null) {
            throw new HttpStatusException(404, "account not found");
        }
        return usage;
    }

    public AdminAccountResponse createAccount(CreateAccountRequest request) {
        String name = normalizeRequiredName(request.name());
        String platform = normalizePlatform(request.platform(), false);
        String type = normalizeType(request.type(), false);
        Map<String, Object> credentials = normalizeAndValidateCredentials(
                platform,
                type,
                normalizeMap(request.credentials(), true)
        );
        Map<String, Object> extra = normalizeMap(request.extra(), false);
        validateCommonConfig(extra);
        Long proxyId = normalizeProxyId(request.proxy_id(), false);
        validateProxyId(proxyId);

        List<Long> groupIds = resolveCreateGroupIds(platform, request.group_ids());
        validateGroupIds(groupIds);
        if (!Boolean.TRUE.equals(request.confirm_mixed_channel_risk())) {
            checkMixedChannelRisk(0L, platform, groupIds);
        }

        long id = repository.createAccount(
                name,
                normalizeNotes(request.notes()),
                platform,
                type,
                credentials,
                extra,
                proxyId,
                normalizeConcurrency(request.concurrency()),
                normalizeLoadFactorForCreate(request.load_factor()),
                normalizePriority(request.priority()),
                normalizeRateMultiplier(request.rate_multiplier()),
                STATUS_ACTIVE,
                true,
                normalizeExpiresAt(request.expires_at()),
                request.auto_pause_on_expired() == null || request.auto_pause_on_expired()
        );

        repository.bindGroups(id, groupIds);
        return getAccount(id);
    }

    public AdminAccountResponse updateAccount(long id, UpdateAccountRequest request) {
        AdminAccountResponse current = getAccount(id);

        String name = request.isNamePresent() ? normalizeRequiredName(request.getName()) : null;
        String notes = request.isNotesPresent() ? normalizeNotes(request.getNotes()) : null;
        String type = request.isTypePresent() ? normalizeType(request.getType(), false) : null;

        Map<String, Object> credentials = null;
        if (request.isCredentialsPresent()) {
            credentials = normalizeAndValidateCredentials(
                    current.platform(),
                    type == null ? current.type() : type,
                    normalizeMap(request.getCredentials(), false)
            );
        }

        Map<String, Object> extra = null;
        if (request.isExtraPresent()) {
            extra = normalizeMap(request.getExtra(), false);
            preserveQuotaUsageFields(extra, current.extra());
            validateCommonConfig(extra);
        }

        Long proxyId = null;
        if (request.isProxyIdPresent()) {
            proxyId = normalizeProxyId(request.getProxy_id(), true);
            validateProxyId(proxyId);
        }

        Integer concurrency = request.isConcurrencyPresent() ? normalizeConcurrency(request.getConcurrency()) : null;
        Integer loadFactor = request.isLoadFactorPresent() ? normalizeLoadFactorForUpdate(request.getLoad_factor()) : null;
        Integer priority = request.isPriorityPresent() ? normalizePriority(request.getPriority()) : null;
        Double rateMultiplier = request.isRateMultiplierPresent() ? normalizeRateMultiplier(request.getRate_multiplier()) : null;
        Boolean schedulable = request.isSchedulablePresent() ? request.getSchedulable() : null;
        String status = request.isStatusPresent() ? normalizeUpdateStatus(request.getStatus()) : null;
        Long expiresAt = request.isExpiresAtPresent() ? normalizeExpiresAt(request.getExpires_at()) : null;
        Boolean autoPauseOnExpired = request.isAutoPauseOnExpiredPresent() ? request.getAuto_pause_on_expired() : null;

        if (request.isGroupIdsPresent()) {
            List<Long> groupIds = normalizeGroupIds(request.getGroup_ids());
            validateGroupIds(groupIds);
            if (!Boolean.TRUE.equals(request.getConfirm_mixed_channel_risk())) {
                checkMixedChannelRisk(id, current.platform(), groupIds);
            }
        }

        int updated = repository.updateAccountColumns(
                id,
                name,
                request.isNotesPresent(),
                notes,
                type,
                request.isCredentialsPresent(),
                credentials == null ? Map.of() : credentials,
                request.isExtraPresent(),
                extra == null ? Map.of() : extra,
                request.isProxyIdPresent(),
                proxyId,
                concurrency,
                loadFactor,
                request.isLoadFactorPresent(),
                priority,
                rateMultiplier,
                schedulable,
                status,
                request.isExpiresAtPresent(),
                expiresAt,
                autoPauseOnExpired
        );
        if (updated == 0) {
            throw new HttpStatusException(404, "account not found");
        }
        if (request.isGroupIdsPresent()) {
            repository.bindGroups(id, normalizeGroupIds(request.getGroup_ids()));
        }
        return getAccount(id);
    }

    public Map<String, String> deleteAccount(long id) {
        getAccount(id);
        repository.deleteAccountGroups(id);
        repository.deleteScheduledTestPlans(id);
        int updated = repository.softDeleteAccount(id);
        if (updated == 0) {
            throw new HttpStatusException(404, "account not found");
        }
        return Map.of("message", "Account deleted successfully");
    }

    public AdminAccountResponse setSchedulable(long id, SetSchedulableRequest request) {
        if (request == null || request.schedulable() == null) {
            throw new IllegalArgumentException("schedulable is required");
        }
        getAccount(id);
        repository.setSchedulable(id, request.schedulable());
        return getAccount(id);
    }

    public TempUnschedulableStatusResponse getTempUnschedulableStatus(long id) {
        AdminAccountResponse account = getAccount(id);
        return repository.buildTempUnschedulableStatus(account.temp_unschedulable_until(), account.temp_unschedulable_reason());
    }

    @Transactional
    public Map<String, String> clearTempUnschedulable(long id) {
        getAccount(id);
        repository.clearTempUnschedulable(id);
        repository.clearModelRateLimits(id);
        return Map.of("message", "Temp unschedulable cleared successfully");
    }

    @Transactional
    public AdminAccountResponse clearError(long id) {
        getAccount(id);
        clearRecoverableRuntimeState(id);
        repository.clearError(id);
        return getAccount(id);
    }

    @Transactional
    public AdminAccountResponse clearRateLimit(long id) {
        getAccount(id);
        clearRecoverableRuntimeState(id);
        return getAccount(id);
    }

    @Transactional
    public AdminAccountResponse recoverState(long id) {
        AdminAccountResponse current = getAccount(id);
        if ("error".equals(current.status())) {
            repository.clearError(id);
        }
        if (hasRecoverableRuntimeState(current)) {
            clearRecoverableRuntimeState(id);
        }
        return getAccount(id);
    }

    @Transactional
    public AdminAccountResponse resetQuota(long id) {
        getAccount(id);
        repository.resetQuotaUsage(id);
        return getAccount(id);
    }

    public WindowStatsResponse getTodayStats(long id, String timezone) {
        getAccount(id);
        return repository.getTodayStats(id, resolveTodayStart(timezone));
    }

    @Transactional
    public Object refreshAccount(long id) {
        AdminAccountResponse current = getAccount(id);
        RefreshResult result = refreshSingleAccount(current);
        return result.warning() == null
                ? getAccount(id)
                : new AccountRefreshWarningResponse(
                "Token refreshed successfully, but project_id could not be retrieved (will retry automatically)",
                result.warning()
        );
    }

    public BatchRefreshResultResponse batchRefresh(BatchRefreshRequest request) {
        List<Long> requestedIds = normalizeRequiredAccountIds(request == null ? null : request.account_ids(), "account_ids is required");
        List<AdminAccountResponse> accounts = repository.getAccountsByIds(requestedIds);
        Map<Long, AdminAccountResponse> accountById = new LinkedHashMap<>();
        for (AdminAccountResponse account : accounts) {
            accountById.put(account.id(), account);
        }

        int success = 0;
        int failed = 0;
        List<BatchOperationErrorResponse> errors = new ArrayList<>();
        List<BatchRefreshWarningResponse> warnings = new ArrayList<>();

        for (Long accountId : requestedIds) {
            AdminAccountResponse account = accountById.get(accountId);
            if (account == null) {
                failed++;
                errors.add(new BatchOperationErrorResponse(accountId, "account not found"));
                continue;
            }
            try {
                RefreshResult result = refreshSingleAccount(account);
                success++;
                if (result.warning() != null) {
                    warnings.add(new BatchRefreshWarningResponse(accountId, result.warning()));
                }
            } catch (Exception ex) {
                failed++;
                errors.add(new BatchOperationErrorResponse(accountId, ex.getMessage()));
            }
        }

        return new BatchRefreshResultResponse(
                requestedIds.size(),
                success,
                failed,
                List.copyOf(errors),
                List.copyOf(warnings)
        );
    }

    public Map<String, String> getAntigravityDefaultModelMapping() {
        return DEFAULT_ANTIGRAVITY_MODEL_MAPPING;
    }

    public AdminDataImportResult importData(AccountDataImportRequest request) {
        AdminDataPayload payload = request == null ? null : request.data();
        validateImportDataHeader(payload);
        boolean skipDefaultGroupBind = request == null || request.skip_default_group_bind() == null || request.skip_default_group_bind();

        int proxyCreated = 0;
        int proxyReused = 0;
        int proxyFailed = 0;
        int accountCreated = 0;
        int accountFailed = 0;
        List<AdminDataImportError> errors = new ArrayList<>();

        Map<String, Long> proxyIdByKey = new LinkedHashMap<>();
        for (AdminProxyResponse proxy : proxyRepository.listAllActive(null, false)) {
            proxyIdByKey.put(buildProxyKey(proxy.protocol(), proxy.host(), proxy.port(), proxy.username(), proxy.password()), proxy.id());
        }

        for (AdminDataProxy item : payload.proxies()) {
            String key = (item.proxy_key() == null || item.proxy_key().isBlank())
                    ? buildProxyKey(item.protocol(), item.host(), item.port(), item.username(), item.password())
                    : item.proxy_key();
            try {
                validateImportProxy(item);
                String normalizedStatus = normalizeImportedProxyStatus(item.status());
                Long existingId = proxyIdByKey.get(key);
                if (existingId != null) {
                    proxyReused++;
                    if (normalizedStatus != null) {
                        proxyRepository.getProxy(existingId).ifPresent(existing -> {
                            if (!normalizedStatus.equals(existing.status())) {
                                proxyRepository.updateProxy(existing.id(), null, null, null, null, null, false, null, false, normalizedStatus);
                            }
                        });
                    }
                    continue;
                }

                long createdId = proxyRepository.createProxy(
                        defaultImportedProxyName(item.name()),
                        normalizeImportedProxyProtocol(item.protocol()),
                        normalizeImportedProxyHost(item.host()),
                        normalizeImportedProxyPort(item.port()),
                        normalizeOptionalText(item.username()),
                        normalizeOptionalText(item.password())
                );
                if (normalizedStatus != null && !"active".equals(normalizedStatus)) {
                    proxyRepository.updateProxy(createdId, null, null, null, null, null, false, null, false, normalizedStatus);
                }
                proxyIdByKey.put(key, createdId);
                proxyCreated++;
            } catch (Exception ex) {
                proxyFailed++;
                errors.add(new AdminDataImportError("proxy", item.name(), key, ex.getMessage()));
            }
        }

        for (AdminDataAccount item : payload.accounts()) {
            try {
                validateImportAccount(item);
                Long proxyId = null;
                if (item.proxy_key() != null && !item.proxy_key().isBlank()) {
                    proxyId = proxyIdByKey.get(item.proxy_key());
                    if (proxyId == null) {
                        throw new IllegalArgumentException("proxy_key not found");
                    }
                }

                String platform = normalizePlatform(item.platform(), false);
                String type = normalizeType(item.type(), false);
                Map<String, Object> credentials = normalizeAndValidateCredentials(
                        platform,
                        type,
                        normalizeMap(item.credentials(), true)
                );
                enrichImportedCredentialsFromIdToken(platform, type, credentials);
                Map<String, Object> extra = normalizeMap(item.extra(), false);
                validateCommonConfig(extra);

                long id = repository.createAccount(
                        normalizeRequiredName(item.name()),
                        normalizeNotes(item.notes()),
                        platform,
                        type,
                        credentials,
                        extra,
                        proxyId,
                        normalizeConcurrency(item.concurrency()),
                        null,
                        normalizePriority(item.priority()),
                        normalizeRateMultiplier(item.rate_multiplier()),
                        STATUS_ACTIVE,
                        true,
                        normalizeExpiresAt(item.expires_at()),
                        item.auto_pause_on_expired() == null || item.auto_pause_on_expired()
                );

                if (!skipDefaultGroupBind) {
                    List<Long> defaultGroupIds = resolveCreateGroupIds(platform, List.of());
                    if (!defaultGroupIds.isEmpty()) {
                        repository.bindGroups(id, defaultGroupIds);
                    }
                }
                accountCreated++;
            } catch (Exception ex) {
                accountFailed++;
                errors.add(new AdminDataImportError("account", item.name(), item.proxy_key(), ex.getMessage()));
            }
        }

        return new AdminDataImportResult(
                proxyCreated,
                proxyReused,
                proxyFailed,
                accountCreated,
                accountFailed,
                errors.isEmpty() ? null : List.copyOf(errors)
        );
    }

    public AdminDataPayload exportData(
            List<Long> ids,
            boolean includeProxies,
            String platform,
            String type,
            String status,
            String group,
            String privacyMode,
            String search,
            String sortBy,
            String sortOrder
    ) {
        List<AdminAccountResponse> accounts = (ids != null && !ids.isEmpty())
                ? repository.getAccountsByIds(ids)
                : collectAllAccounts(
                normalizePlatform(platform, true),
                normalizeType(type, true),
                normalizeFilterStatus(status),
                normalizeGroupFilter(group),
                normalizePrivacyMode(privacyMode),
                normalizeSearch(search),
                sortBy == null || sortBy.isBlank() ? "name" : sortBy,
                sortOrder == null || sortOrder.isBlank() ? "asc" : sortOrder
        );

        Map<Long, AdminDataProxy> proxyPayloadById = includeProxies ? loadProxyPayloadById(accounts) : Map.of();
        List<AdminDataProxy> proxies = includeProxies ? new ArrayList<>(proxyPayloadById.values()) : List.of();
        Map<Long, String> proxyKeyById = new LinkedHashMap<>();
        for (Map.Entry<Long, AdminDataProxy> entry : proxyPayloadById.entrySet()) {
            proxyKeyById.put(entry.getKey(), entry.getValue().proxy_key());
        }

        List<AdminDataAccount> dataAccounts = new ArrayList<>(accounts.size());
        for (AdminAccountResponse account : accounts) {
            String proxyKey = account.proxy_id() == null ? null : proxyKeyById.get(account.proxy_id());
            dataAccounts.add(new AdminDataAccount(
                    account.name(),
                    account.notes(),
                    account.platform(),
                    account.type(),
                    maskCredentials(account.credentials()),
                    account.extra(),
                    proxyKey,
                    account.concurrency(),
                    account.priority(),
                    account.rate_multiplier(),
                    account.expires_at(),
                    account.auto_pause_on_expired()
            ));
        }

        return new AdminDataPayload(
                null,
                null,
                Instant.now().toString(),
                proxies,
                dataAccounts
        );
    }

    public BatchTodayStatsResponse getBatchTodayStats(BatchTodayStatsRequest request, String timezone) {
        List<Long> accountIds = normalizeRequiredAccountIds(request == null ? null : request.account_ids(), "account_ids is required");
        return new BatchTodayStatsResponse(repository.getTodayStatsBatch(accountIds, resolveTodayStart(timezone)));
    }

    public BatchOperationResultResponse batchClearError(BatchClearErrorRequest request) {
        List<Long> accountIds = normalizeRequiredAccountIds(request == null ? null : request.account_ids(), "account_ids is required");
        List<BatchOperationErrorResponse> errors = new ArrayList<>();
        int successCount = 0;
        for (Long accountId : accountIds) {
            try {
                clearError(accountId);
                successCount++;
            } catch (Exception ex) {
                errors.add(new BatchOperationErrorResponse(accountId, ex.getMessage()));
            }
        }
        return new BatchOperationResultResponse(accountIds.size(), successCount, errors.size(), errors);
    }

    public PreviewFromCrsResult previewFromCrs(PreviewFromCrsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return crsSyncService.previewFromCrs(request);
    }

    public SyncFromCrsResult syncFromCrs(SyncFromCrsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        return crsSyncService.syncFromCrs(request);
    }

    @Transactional
    public RefreshTierResponse refreshTier(long id) {
        AdminAccountResponse account = getAccount(id);
        GeminiTierRefreshService.RefreshTierComputation computation = geminiTierRefreshService.refreshGoogleOneTier(
                account,
                loadProxyForAccount(account)
        );
        persistAccountCredentialsAndExtra(id, computation.credentials(), computation.extra());
        return computation.response();
    }

    public BatchOperationResultResponse batchRefreshTier(BatchRefreshTierRequest request) {
        List<AdminAccountResponse> candidates;
        if (request == null || request.account_ids() == null || request.account_ids().isEmpty()) {
            candidates = collectAllAccounts("gemini", "oauth", null, null, null, null, "name", "asc");
        } else {
            candidates = repository.getAccountsByIds(normalizeRequiredAccountIds(request.account_ids(), "account_ids is required"));
        }

        List<AdminAccountResponse> accounts = candidates.stream()
                .filter(this::isGoogleOneGeminiAccount)
                .toList();

        int successCount = 0;
        List<BatchOperationErrorResponse> errors = new ArrayList<>();
        for (AdminAccountResponse account : accounts) {
            try {
                GeminiTierRefreshService.RefreshTierComputation computation = geminiTierRefreshService.refreshGoogleOneTier(
                        account,
                        loadProxyForAccount(account)
                );
                persistAccountCredentialsAndExtra(account.id(), computation.credentials(), computation.extra());
                successCount++;
            } catch (Exception ex) {
                errors.add(new BatchOperationErrorResponse(account.id(), ex.getMessage()));
            }
        }
        return new BatchOperationResultResponse(accounts.size(), successCount, errors.size(), List.copyOf(errors));
    }

    @Transactional
    public AdminAccountResponse setPrivacy(long id) {
        AdminAccountResponse account = getAccount(id);
        if (!TYPE_OAUTH.equals(account.type())) {
            throw new IllegalArgumentException("Only OAuth accounts support privacy setting");
        }
        String mode = accountPrivacyService.forcePrivacy(account, loadProxyForAccount(account));
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("Cannot set privacy: missing access_token");
        }
        persistAccountExtra(id, accountPrivacyService.mergePrivacyMode(account.extra(), mode));
        return getAccount(id);
    }

    public BatchCreateAccountsResponse batchCreate(BatchCreateAccountsRequest request) {
        if (request == null || request.accounts() == null || request.accounts().isEmpty()) {
            throw new IllegalArgumentException("accounts is required");
        }
        int success = 0;
        int failed = 0;
        List<BatchCreateAccountResultResponse> results = new ArrayList<>(request.accounts().size());
        for (CreateAccountRequest item : request.accounts()) {
            try {
                AdminAccountResponse created = createAccount(item);
                success++;
                results.add(new BatchCreateAccountResultResponse(item.name(), created.id(), true, null));
            } catch (Exception ex) {
                failed++;
                results.add(new BatchCreateAccountResultResponse(item.name(), null, false, ex.getMessage()));
            }
        }
        return new BatchCreateAccountsResponse(success, failed, List.copyOf(results));
    }

    public BulkUpdateAccountsResponse batchUpdateCredentials(BatchUpdateCredentialsRequest request) {
        List<Long> accountIds = normalizeRequiredAccountIds(request == null ? null : request.account_ids(), "account_ids is required");
        String field = normalizeBatchCredentialField(request == null ? null : request.field());
        validateBatchCredentialValue(field, request == null ? null : request.value());

        List<PendingCredentialUpdate> updates = new ArrayList<>(accountIds.size());
        for (Long accountId : accountIds) {
            AdminAccountResponse account = repository.getAccount(accountId).orElseThrow(
                    () -> new HttpStatusException(404, "Account " + accountId + " not found")
            );
            Map<String, Object> credentials = account.credentials() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(account.credentials());
            credentials.put(field, request.value());
            updates.add(new PendingCredentialUpdate(accountId, credentials));
        }

        List<Long> successIds = new ArrayList<>();
        List<Long> failedIds = new ArrayList<>();
        List<BulkUpdateAccountResultResponse> results = new ArrayList<>(updates.size());
        for (PendingCredentialUpdate update : updates) {
            try {
                int updated = repository.updateAccountColumns(
                        update.accountId(),
                        null,
                        false,
                        null,
                        null,
                        true,
                        update.credentials(),
                        false,
                        Map.of(),
                        false,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null
                );
                if (updated == 0) {
                    throw new HttpStatusException(404, "account not found");
                }
                successIds.add(update.accountId());
                results.add(new BulkUpdateAccountResultResponse(update.accountId(), true, null));
            } catch (Exception ex) {
                failedIds.add(update.accountId());
                results.add(new BulkUpdateAccountResultResponse(update.accountId(), false, ex.getMessage()));
            }
        }

        return new BulkUpdateAccountsResponse(
                successIds.size(),
                failedIds.size(),
                List.copyOf(successIds),
                List.copyOf(failedIds),
                List.copyOf(results)
        );
    }

    public BulkUpdateAccountsResponse bulkUpdate(BulkUpdateAccountsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("account_ids or filters is required");
        }
        if (request.rate_multiplier() != null && request.rate_multiplier() < 0) {
            throw new IllegalArgumentException("rate_multiplier must be >= 0");
        }
        boolean hasAccountIds = request.account_ids() != null && !request.account_ids().isEmpty();
        if (!hasAccountIds && request.filters() == null) {
            throw new IllegalArgumentException("account_ids or filters is required");
        }
        if (!hasBulkUpdates(request)) {
            throw new IllegalArgumentException("No updates provided");
        }

        List<Long> targetIds = hasAccountIds
                ? normalizeGroupIds(request.account_ids())
                : resolveBulkUpdateTargetIds(request.filters());
        if (targetIds.isEmpty()) {
            return new BulkUpdateAccountsResponse(0, 0, List.of(), List.of(), List.of());
        }

        Long proxyId = null;
        boolean proxyIdPresent = request.proxy_id() != null;
        if (proxyIdPresent) {
            proxyId = normalizeProxyId(request.proxy_id(), true);
            validateProxyId(proxyId);
        }

        Integer concurrency = request.concurrency() == null ? null : normalizeConcurrency(request.concurrency());
        Integer priority = request.priority() == null ? null : normalizePriority(request.priority());
        Double rateMultiplier = request.rate_multiplier() == null ? null : normalizeRateMultiplier(request.rate_multiplier());
        Integer loadFactor = normalizeBulkLoadFactor(request.load_factor());
        boolean loadFactorPresent = request.load_factor() != null;
        String status = request.status() == null || request.status().isBlank() ? null : normalizeUpdateStatus(request.status());
        String name = normalizeOptionalText(request.name());
        Map<String, Object> credentialsPatch = normalizeBulkPatchMap(request.credentials());
        validateBulkCredentialPatch(targetIds, credentialsPatch);
        Map<String, Object> extraPatch = normalizeBulkPatchMap(request.extra());
        sanitizeExtraBaseRpm(extraPatch);
        if (!extraPatch.isEmpty()) {
            validateCommonConfig(extraPatch);
        }

        List<Long> groupIds = request.group_ids() == null ? null : normalizeGroupIds(request.group_ids());
        if (groupIds != null) {
            validateGroupIds(groupIds);
            if (!Boolean.TRUE.equals(request.confirm_mixed_channel_risk())) {
                Map<Long, String> platformById = loadPlatformByAccountId(targetIds);
                for (Long accountId : targetIds) {
                    String platform = platformById.get(accountId);
                    if (platform != null && !platform.isBlank()) {
                        checkMixedChannelRisk(accountId, platform, groupIds);
                    }
                }
            }
        }

        repository.bulkUpdateAccounts(
                targetIds,
                name,
                proxyIdPresent,
                proxyId,
                concurrency,
                priority,
                rateMultiplier,
                loadFactorPresent,
                loadFactor,
                status,
                request.schedulable(),
                credentialsPatch,
                extraPatch
        );

        List<Long> successIds = new ArrayList<>();
        List<Long> failedIds = new ArrayList<>();
        List<BulkUpdateAccountResultResponse> results = new ArrayList<>(targetIds.size());
        for (Long accountId : targetIds) {
            if (groupIds != null) {
                try {
                    repository.bindGroups(accountId, groupIds);
                } catch (Exception ex) {
                    failedIds.add(accountId);
                    results.add(new BulkUpdateAccountResultResponse(accountId, false, ex.getMessage()));
                    continue;
                }
            }
            successIds.add(accountId);
            results.add(new BulkUpdateAccountResultResponse(accountId, true, null));
        }

        return new BulkUpdateAccountsResponse(
                successIds.size(),
                failedIds.size(),
                List.copyOf(successIds),
                List.copyOf(failedIds),
                List.copyOf(results)
        );
    }

    public CheckMixedChannelResponse checkMixedChannelRisk(CheckMixedChannelRequest request) {
        List<Long> groupIds = normalizeGroupIds(request.group_ids());
        if (groupIds.isEmpty()) {
            return new CheckMixedChannelResponse(false, null, null, null);
        }
        try {
            checkMixedChannelRisk(request.account_id() == null ? 0L : request.account_id(), request.platform(), groupIds);
            return new CheckMixedChannelResponse(false, null, null, null);
        } catch (MixedChannelConflictException ex) {
            return new CheckMixedChannelResponse(
                    true,
                    "mixed_channel_warning",
                    ex.getMessage(),
                    new MixedChannelWarningDetailsResponse(
                            ex.getGroupId(),
                            ex.getGroupName(),
                            ex.getCurrentPlatform(),
                            ex.getOtherPlatform()
                    )
            );
        }
    }

    private void checkMixedChannelRisk(long currentAccountId, String currentPlatformRaw, List<Long> groupIds) {
        String currentPlatform = mixedChannelPlatform(currentPlatformRaw);
        if (currentPlatform == null || groupIds.isEmpty()) {
            return;
        }
        for (Long groupId : groupIds) {
            for (MixedChannelCandidate candidate : repository.listAccountsByGroup(groupId)) {
                if (currentAccountId > 0 && candidate.accountId() == currentAccountId) {
                    continue;
                }
                String otherPlatform = mixedChannelPlatform(candidate.platform());
                if (otherPlatform == null) {
                    continue;
                }
                if (!Objects.equals(currentPlatform, otherPlatform)) {
                    throw new MixedChannelConflictException(
                            candidate.groupId(),
                            candidate.groupName(),
                            currentPlatform,
                            otherPlatform
                    );
                }
            }
        }
    }

    private String mixedChannelPlatform(String raw) {
        String normalized = normalizePlatform(raw, true);
        if (normalized == null) {
            return null;
        }
        if ("antigravity".equals(normalized)) {
            return "Antigravity";
        }
        if ("anthropic".equals(normalized) || "claude".equals(normalized)) {
            return "Anthropic";
        }
        return null;
    }

    private static Map<String, String> buildDefaultAntigravityModelMapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("claude-opus-4-7", "claude-opus-4-7");
        mapping.put("claude-opus-4-6-thinking", "claude-opus-4-6-thinking");
        mapping.put("claude-opus-4-6", "claude-opus-4-6-thinking");
        mapping.put("claude-opus-4-5-thinking", "claude-opus-4-6-thinking");
        mapping.put("claude-sonnet-4-6", "claude-sonnet-4-6");
        mapping.put("claude-sonnet-4-5", "claude-sonnet-4-5");
        mapping.put("claude-sonnet-4-5-thinking", "claude-sonnet-4-5-thinking");
        mapping.put("claude-opus-4-5-20251101", "claude-opus-4-6-thinking");
        mapping.put("claude-sonnet-4-5-20250929", "claude-sonnet-4-5");
        mapping.put("claude-haiku-4-5", "claude-sonnet-4-6");
        mapping.put("claude-haiku-4-5-20251001", "claude-sonnet-4-6");
        mapping.put("gemini-2.5-flash", "gemini-2.5-flash");
        mapping.put("gemini-2.5-flash-image", "gemini-2.5-flash-image");
        mapping.put("gemini-2.5-flash-image-preview", "gemini-2.5-flash-image");
        mapping.put("gemini-2.5-flash-lite", "gemini-2.5-flash-lite");
        mapping.put("gemini-2.5-flash-thinking", "gemini-2.5-flash-thinking");
        mapping.put("gemini-2.5-pro", "gemini-2.5-pro");
        mapping.put("gemini-3-flash", "gemini-3-flash");
        mapping.put("gemini-3-pro-high", "gemini-3-pro-high");
        mapping.put("gemini-3-pro-low", "gemini-3-pro-low");
        mapping.put("gemini-3-flash-preview", "gemini-3-flash");
        mapping.put("gemini-3-pro-preview", "gemini-3-pro-high");
        mapping.put("gemini-3.1-pro-high", "gemini-3.1-pro-high");
        mapping.put("gemini-3.1-pro-low", "gemini-3.1-pro-low");
        mapping.put("gemini-3.1-pro-preview", "gemini-3.1-pro-high");
        mapping.put("gemini-3.1-flash-image", "gemini-3.1-flash-image");
        mapping.put("gemini-3.1-flash-image-preview", "gemini-3.1-flash-image");
        mapping.put("gemini-3-pro-image", "gemini-3.1-flash-image");
        mapping.put("gemini-3-pro-image-preview", "gemini-3.1-flash-image");
        mapping.put("gpt-oss-120b-medium", "gpt-oss-120b-medium");
        mapping.put("tab_flash_lite_preview", "tab_flash_lite_preview");
        return Collections.unmodifiableMap(mapping);
    }

    private void validateImportDataHeader(AdminDataPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("data is required");
        }
        if (payload.type() != null && !payload.type().isBlank()
                && !DATA_TYPE.equals(payload.type())
                && !LEGACY_DATA_TYPE.equals(payload.type())) {
            throw new IllegalArgumentException("unsupported data type: " + payload.type());
        }
        if (payload.version() != null && payload.version() != 0 && payload.version() != DATA_VERSION) {
            throw new IllegalArgumentException("unsupported data version: " + payload.version());
        }
        if (payload.proxies() == null) {
            throw new IllegalArgumentException("proxies is required");
        }
        if (payload.accounts() == null) {
            throw new IllegalArgumentException("accounts is required");
        }
    }

    private void validateImportProxy(AdminDataProxy item) {
        normalizeImportedProxyProtocol(item.protocol());
        normalizeImportedProxyHost(item.host());
        normalizeImportedProxyPort(item.port());
        if (item.status() != null && !item.status().isBlank()) {
            normalizeImportedProxyStatus(item.status());
        }
    }

    private void validateImportAccount(AdminDataAccount item) {
        normalizeRequiredName(item.name());
        normalizePlatform(item.platform(), false);
        normalizeType(item.type(), false);
        normalizeMap(item.credentials(), true);
        normalizeRateMultiplier(item.rate_multiplier());
        if (item.concurrency() < 0) {
            throw new IllegalArgumentException("concurrency must be >= 0");
        }
        if (item.priority() < 0) {
            throw new IllegalArgumentException("priority must be >= 0");
        }
    }

    private String defaultImportedProxyName(String name) {
        String normalized = normalizeOptionalText(name);
        return normalized == null ? "imported-proxy" : normalized;
    }

    private String normalizeImportedProxyProtocol(String value) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            throw new IllegalArgumentException("proxy protocol is required");
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!List.of("http", "https", "socks5", "socks5h").contains(normalized)) {
            throw new IllegalArgumentException("proxy protocol is invalid: " + value);
        }
        return normalized;
    }

    private String normalizeImportedProxyHost(String value) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            throw new IllegalArgumentException("proxy host is required");
        }
        return normalized;
    }

    private int normalizeImportedProxyPort(int value) {
        if (value <= 0 || value > 65535) {
            throw new IllegalArgumentException("proxy port is invalid");
        }
        return value;
    }

    private String normalizeImportedProxyStatus(String value) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "active" -> "active";
            case "inactive", "disabled" -> "inactive";
            default -> throw new IllegalArgumentException("proxy status is invalid: " + value);
        };
    }

    private void validateGroupIds(List<Long> groupIds) {
        Set<Long> existing = repository.existingGroupIds(groupIds);
        for (Long groupId : groupIds) {
            if (!existing.contains(groupId)) {
                throw new IllegalArgumentException("get group: group not found");
            }
        }
    }

    private void validateProxyId(Long proxyId) {
        if (proxyId != null && proxyId > 0 && !repository.proxyExists(proxyId)) {
            throw new IllegalArgumentException("proxy not found");
        }
    }

    private void validateCommonConfig(Map<String, Object> extra) {
        repository.validateQuotaResetConfig(extra);
        repository.computeQuotaResetAt(extra);
    }

    private Map<String, Object> normalizeAndValidateCredentials(String platform, String type, Map<String, Object> credentials) {
        return upstreamUrlGuard.normalizeAccountCredentials(platform, type, credentials);
    }

    private void validateBulkCredentialPatch(List<Long> targetIds, Map<String, Object> credentialsPatch) {
        if (credentialsPatch == null || credentialsPatch.isEmpty() || !credentialsPatch.containsKey("base_url")) {
            return;
        }
        for (AdminAccountResponse account : repository.getAccountsByIds(targetIds)) {
            upstreamUrlGuard.validateAccountCredentialsPatch(
                    account.platform(),
                    account.type(),
                    account.credentials(),
                    credentialsPatch
            );
        }
    }

    private List<Long> resolveCreateGroupIds(String platform, List<Long> requestedGroupIds) {
        List<Long> normalized = normalizeGroupIds(requestedGroupIds);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return repository.findDefaultGroupId(platform).map(List::of).orElse(List.of());
    }

    private void clearRecoverableRuntimeState(long id) {
        repository.clearRateLimit(id);
        repository.clearAntigravityQuotaScopes(id);
        repository.clearModelRateLimits(id);
        repository.clearTempUnschedulable(id);
    }

    private boolean hasRecoverableRuntimeState(AdminAccountResponse account) {
        if (account == null) {
            return false;
        }
        if (account.rate_limited_at() != null
                || account.rate_limit_reset_at() != null
                || account.overload_until() != null
                || account.temp_unschedulable_until() != null) {
            return true;
        }
        Map<String, Object> extra = account.extra();
        return hasNonEmptyValue(extra, "model_rate_limits") || hasNonEmptyValue(extra, "antigravity_quota_scopes");
    }

    private boolean hasNonEmptyValue(Map<String, Object> extra, String key) {
        if (extra == null) {
            return false;
        }
        Object value = extra.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?> mapValue) {
            return !mapValue.isEmpty();
        }
        if (value instanceof List<?> listValue) {
            return !listValue.isEmpty();
        }
        if (value instanceof String stringValue) {
            return !stringValue.isBlank();
        }
        return true;
    }

    private List<Long> normalizeRequiredAccountIds(List<Long> accountIds, String message) {
        List<Long> normalized = normalizeGroupIds(accountIds);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private boolean hasBulkUpdates(BulkUpdateAccountsRequest request) {
        return normalizeOptionalText(request.name()) != null
                || request.proxy_id() != null
                || request.concurrency() != null
                || request.priority() != null
                || request.rate_multiplier() != null
                || request.load_factor() != null
                || normalizeOptionalText(request.status()) != null
                || request.schedulable() != null
                || request.group_ids() != null
                || (request.credentials() != null && !request.credentials().isEmpty())
                || (request.extra() != null && !request.extra().isEmpty());
    }

    private List<Long> resolveBulkUpdateTargetIds(BulkUpdateAccountFiltersRequest filters) {
        if (filters == null) {
            return List.of();
        }
        String group = filters.group();
        if (group != null && !group.isBlank() && !"ungrouped".equals(group.trim())) {
            try {
                Long.parseLong(group.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("invalid group filter: " + ex.getMessage());
            }
        }

        int page = 1;
        final int pageSize = 500;
        List<Long> accountIds = new ArrayList<>(pageSize);
        while (true) {
            PageResponse<AdminAccountResponse> pageResponse = repository.listAccounts(
                    page,
                    pageSize,
                    normalizePlatform(filters.platform(), true),
                    normalizeType(filters.type(), true),
                    normalizeFilterStatus(filters.status()),
                    normalizeGroupFilter(filters.group()),
                    normalizePrivacyMode(filters.privacy_mode()),
                    normalizeSearch(filters.search()),
                    "",
                    "",
                    false
            );
            for (AdminAccountResponse item : pageResponse.items()) {
                accountIds.add(item.id());
            }
            if (accountIds.size() >= pageResponse.total() || pageResponse.items().isEmpty()) {
                return accountIds;
            }
            page++;
        }
    }

    private List<AdminAccountResponse> collectAllAccounts(
            String platform,
            String type,
            String status,
            String group,
            String privacyMode,
            String search,
            String sortBy,
            String sortOrder
    ) {
        int page = 1;
        final int pageSize = 1000;
        List<AdminAccountResponse> out = new ArrayList<>();
        while (true) {
            PageResponse<AdminAccountResponse> response = repository.listAccounts(
                    page,
                    pageSize,
                    platform,
                    type,
                    status,
                    group,
                    privacyMode,
                    search,
                    sortBy,
                    sortOrder,
                    false
            );
            out.addAll(response.items());
            if (out.size() >= response.total() || response.items().isEmpty()) {
                return out;
            }
            page++;
        }
    }

    private AdminProxyResponse loadProxyForAccount(AdminAccountResponse account) {
        if (account == null || account.proxy_id() == null || account.proxy_id() <= 0) {
            return null;
        }
        return proxyRepository.getProxy(account.proxy_id()).orElse(null);
    }

    private boolean isGoogleOneGeminiAccount(AdminAccountResponse account) {
        if (account == null) {
            return false;
        }
        if (!"gemini".equals(account.platform()) || !"oauth".equals(account.type())) {
            return false;
        }
        return "google_one".equals(stringValue(account.credentials() == null ? null : account.credentials().get("oauth_type")));
    }

    private RefreshResult refreshSingleAccount(AdminAccountResponse account) {
        if (account == null) {
            throw new HttpStatusException(404, "account not found");
        }
        if (!TYPE_OAUTH.equals(account.type())) {
            throw new IllegalArgumentException("cannot refresh non-OAuth account");
        }

        if ("openai".equals(account.platform())) {
            OpenAiOAuthTokenResponse tokenInfo = openAiOAuthService.refreshAccountToken(account);
            Map<String, Object> credentials = mergeCredentials(
                    account.credentials(),
                    openAiOAuthService.buildAccountCredentials(tokenInfo)
            );
            Map<String, Object> extra = openAiOAuthService.mergeExtra(account.extra(), tokenInfo);
            persistAccountCredentialsAndExtra(account.id(), credentials, extra);
            return new RefreshResult(null);
        }

        if ("gemini".equals(account.platform())) {
            GeminiOAuthTokenResponse tokenInfo = geminiOAuthGatewayService.refreshAccountToken(account, loadProxyForAccount(account));
            Map<String, Object> credentials = mergeCredentials(
                    account.credentials(),
                    geminiOAuthGatewayService.buildAccountCredentials(tokenInfo)
            );
            Map<String, Object> extra = geminiOAuthGatewayService.mergeExtra(account.extra(), tokenInfo);
            persistAccountCredentialsAndExtra(account.id(), credentials, extra);
            return new RefreshResult(null);
        }

        if ("antigravity".equals(account.platform())) {
            AntigravityOAuthTokenResponse tokenInfo = antigravityOAuthService.refreshAccountToken(account);
            Map<String, Object> credentials = mergeCredentials(
                    account.credentials(),
                    antigravityOAuthService.buildAccountCredentials(tokenInfo)
            );
            Map<String, Object> extra = accountPrivacyService.mergePrivacyMode(account.extra(), stringValue(tokenInfo.privacy_mode()));
            persistAccountCredentialsAndExtra(account.id(), credentials, extra);
            String warning = stringValue(credentials.get("project_id")) == null
                    ? "missing_project_id_temporary"
                    : null;
            return new RefreshResult(warning);
        }

        ClaudeOAuthTokenResponse tokenInfo = claudeOAuthService.refreshAccountToken(account);
        Map<String, Object> credentials = mergeCredentials(
                account.credentials(),
                claudeOAuthService.buildAccountCredentials(tokenInfo)
        );
        persistAccountCredentialsAndExtra(account.id(), credentials, account.extra() == null ? Map.of() : account.extra());
        return new RefreshResult(null);
    }

    private Map<String, Object> mergeCredentials(Map<String, Object> current, Map<String, Object> updates) {
        Map<String, Object> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        if (updates != null) {
            merged.putAll(updates);
        }
        return merged;
    }

    private void persistAccountCredentialsAndExtra(long accountId, Map<String, Object> credentials, Map<String, Object> extra) {
        AdminAccountResponse current = getAccount(accountId);
        Map<String, Object> normalizedCredentials = normalizeAndValidateCredentials(current.platform(), current.type(), credentials);
        int updated = repository.updateAccountColumns(
                accountId,
                null,
                false,
                null,
                null,
                true,
                normalizedCredentials,
                true,
                extra,
                false,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null
        );
        if (updated == 0) {
            throw new HttpStatusException(404, "account not found");
        }
    }

    private void persistAccountExtra(long accountId, Map<String, Object> extra) {
        int updated = repository.updateAccountColumns(
                accountId,
                null,
                false,
                null,
                null,
                false,
                Map.of(),
                true,
                extra,
                false,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null
        );
        if (updated == 0) {
            throw new HttpStatusException(404, "account not found");
        }
    }

    private Map<Long, AdminDataProxy> loadProxyPayloadById(List<AdminAccountResponse> accounts) {
        LinkedHashSet<Long> proxyIds = new LinkedHashSet<>();
        for (AdminAccountResponse account : accounts) {
            if (account.proxy_id() != null && account.proxy_id() > 0) {
                proxyIds.add(account.proxy_id());
            }
        }
        if (proxyIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, AdminProxyResponse> proxyById = new LinkedHashMap<>();
        for (AdminProxyResponse proxy : proxyRepository.listByIds(List.copyOf(proxyIds))) {
            proxyById.put(proxy.id(), proxy);
        }
        Map<Long, AdminDataProxy> proxiesById = new LinkedHashMap<>();
        for (Long proxyId : proxyIds) {
            AdminProxyResponse proxy = proxyById.get(proxyId);
            if (proxy == null) {
                continue;
            }
            String protocol = proxy.protocol();
            String host = proxy.host();
            int port = proxy.port();
            String username = proxy.username();
            String maskedPassword = maskSensitiveString(proxy.password());
            proxiesById.put(proxyId, new AdminDataProxy(
                    buildProxyKey(protocol, host, port, username, proxy.password()),
                    proxy.name(),
                    protocol,
                    host,
                    port,
                    username,
                    maskedPassword,
                    proxy.status()
            ));
        }
        return proxiesById;
    }

    private String buildProxyKey(String protocol, String host, int port, String username, String password) {
        return String.join("|",
                defaultString(protocol),
                defaultString(host),
                String.valueOf(port),
                defaultString(username),
                defaultString(password));
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private void enrichImportedCredentialsFromIdToken(String platform, String type, Map<String, Object> credentials) {
        if (!"openai".equals(platform) || !"oauth".equals(type) || credentials == null) {
            return;
        }
        String idToken = objectToTrimmedString(credentials.get("id_token"));
        if (idToken == null) {
            return;
        }
        OpenAiIdTokenClaims claims = decodeOpenAiIdToken(idToken);
        if (claims == null) {
            return;
        }
        OpenAiUserInfo userInfo = claims.toUserInfo();
        setCredentialIfMissing(credentials, "email", userInfo.email());
        setCredentialIfMissing(credentials, "plan_type", userInfo.planType());
        setCredentialIfMissing(credentials, "chatgpt_account_id", userInfo.chatgptAccountId());
        setCredentialIfMissing(credentials, "chatgpt_user_id", userInfo.chatgptUserId());
        setCredentialIfMissing(credentials, "organization_id", userInfo.organizationId());
    }

    private void setCredentialIfMissing(Map<String, Object> credentials, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String existing = objectToTrimmedString(credentials.get(key));
        if (existing == null) {
            credentials.put(key, value);
        }
    }

    private String objectToTrimmedString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private OpenAiIdTokenClaims decodeOpenAiIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            return objectMapper.readValue(decoded, OpenAiIdTokenClaims.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String padBase64(String value) {
        int mod = value.length() % 4;
        if (mod == 2) {
            return value + "==";
        }
        if (mod == 3) {
            return value + "=";
        }
        return value;
    }

    private Map<Long, String> loadPlatformByAccountId(List<Long> accountIds) {
        Map<Long, String> platformById = new LinkedHashMap<>();
        for (AdminAccountResponse account : repository.getAccountsByIds(accountIds)) {
            platformById.put(account.id(), account.platform());
        }
        return platformById;
    }

    private String normalizeBatchCredentialField(String field) {
        String normalized = normalizeOptionalText(field);
        if (!Set.of("account_uuid", "org_uuid", "intercept_warmup_requests").contains(normalized)) {
            throw new IllegalArgumentException("field must be one of: account_uuid, org_uuid, intercept_warmup_requests");
        }
        return normalized;
    }

    private void validateBatchCredentialValue(String field, Object value) {
        if ("intercept_warmup_requests".equals(field)) {
            if (!(value instanceof Boolean)) {
                throw new IllegalArgumentException("intercept_warmup_requests must be boolean");
            }
            return;
        }
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException(field + " must be string or null");
        }
    }

    private Integer normalizeBulkLoadFactor(Integer value) {
        if (value == null) {
            return null;
        }
        if (value > 10000) {
            throw new IllegalArgumentException("load_factor must be <= 10000");
        }
        return value <= 0 ? null : value;
    }

    private Map<String, Object> normalizeBulkPatchMap(Map<String, Object> map) {
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    private void sanitizeExtraBaseRpm(Map<String, Object> extra) {
        if (extra == null || !extra.containsKey("base_rpm")) {
            return;
        }
        Integer value = parseIntegerValue(extra.get("base_rpm"));
        if (value == null) {
            return;
        }
        if (value < 0) {
            value = 0;
        } else if (value > 10000) {
            value = 10000;
        }
        extra.put("base_rpm", value);
    }

    private Integer parseIntegerValue(Object value) {
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

    private Instant resolveTodayStart(String timezone) {
        ZoneId zoneId = resolveZoneId(timezone);
        return LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant();
    }

    private ZoneId resolveZoneId(String timezone) {
        ZoneId zoneId;
        try {
            zoneId = timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
        } catch (Exception ex) {
            zoneId = ZoneId.systemDefault();
        }
        return zoneId;
    }

    private int normalizeStatsDays(Integer days) {
        if (days == null) {
            return 30;
        }
        return Math.max(1, Math.min(days, 90));
    }

    private String normalizeUsageSource(String source) {
        return "passive".equalsIgnoreCase(normalizeOptionalText(source)) ? "passive" : "active";
    }

    private List<Long> normalizeGroupIds(List<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        return groupIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
    }

    private void preserveQuotaUsageFields(Map<String, Object> targetExtra, Map<String, Object> currentExtra) {
        if (targetExtra == null || currentExtra == null) {
            return;
        }
        for (String key : List.of("quota_used", "quota_daily_used", "quota_daily_start", "quota_weekly_used", "quota_weekly_start")) {
            if (currentExtra.containsKey(key)) {
                targetExtra.put(key, currentExtra.get(key));
            }
        }
    }

    private String normalizeRequiredName(String value) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            throw new IllegalArgumentException("name is required");
        }
        return normalized;
    }

    private String normalizeNotes(String notes) {
        return normalizeOptionalText(notes);
    }

    private String normalizePlatform(String value, boolean allowNull) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            if (allowNull) {
                return null;
            }
            throw new IllegalArgumentException("platform is required");
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!Set.of("anthropic", "claude", "gemini", "openai", "antigravity").contains(normalized)) {
            throw new IllegalArgumentException("platform is invalid");
        }
        return "claude".equals(normalized) ? "anthropic" : normalized;
    }

    private String normalizeType(String value, boolean allowNull) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            if (allowNull) {
                return null;
            }
            throw new IllegalArgumentException("type is required");
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("type is invalid");
        }
        return normalized;
    }

    private String normalizeFilterStatus(String value) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (Set.of("active", "rate_limited", "temp_unschedulable", "unschedulable", "inactive", "error", "disabled").contains(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("status is invalid");
    }

    private String normalizeUpdateStatus(String value) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "active" -> STATUS_ACTIVE;
            case "inactive", "disabled" -> STATUS_DISABLED;
            case "error" -> STATUS_ERROR;
            default -> throw new IllegalArgumentException("status is invalid");
        };
    }

    private String normalizeGroupFilter(String value) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            return null;
        }
        if ("ungrouped".equals(normalized)) {
            return normalized;
        }
        try {
            long parsed = Long.parseLong(normalized);
            if (parsed < 0) {
                throw new IllegalArgumentException("invalid group filter");
            }
            return Long.toString(parsed);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid group filter");
        }
    }

    private String normalizePrivacyMode(String value) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            return null;
        }
        return normalized;
    }

    private String normalizeSearch(String value) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            return null;
        }
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }

    private int normalizeConcurrency(Integer value) {
        if (value == null || value <= 0) {
            return 1;
        }
        return value;
    }

    private Integer normalizeLoadFactorForCreate(Integer value) {
        if (value == null || value <= 0) {
            return null;
        }
        if (value > 10000) {
            throw new IllegalArgumentException("load_factor must be <= 10000");
        }
        return value;
    }

    private Integer normalizeLoadFactorForUpdate(Integer value) {
        if (value == null || value <= 0) {
            return null;
        }
        if (value > 10000) {
            throw new IllegalArgumentException("load_factor must be <= 10000");
        }
        return value;
    }

    private int normalizePriority(Integer value) {
        return value == null ? 1 : value;
    }

    private Double normalizeRateMultiplier(Double value) {
        if (value == null) {
            return null;
        }
        if (value < 0) {
            throw new IllegalArgumentException("rate_multiplier must be >= 0");
        }
        return value;
    }

    private Long normalizeProxyId(Long value, boolean allowZeroClear) {
        if (value == null) {
            return null;
        }
        if (value == 0 && allowZeroClear) {
            return null;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("proxy_id is invalid");
        }
        return value;
    }

    private Long normalizeExpiresAt(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private Map<String, Object> normalizeMap(Map<String, Object> map, boolean requireNotEmpty) {
        Map<String, Object> normalized = map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        if (requireNotEmpty && normalized.isEmpty()) {
            throw new IllegalArgumentException("credentials is required");
        }
        return normalized;
    }

    private String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String maskSensitiveString(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() > 4) {
            return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
        }
        return "****";
    }

    private Map<String, Object> maskCredentials(Map<String, Object> credentials) {
        if (credentials == null) {
            return null;
        }
        Map<String, Object> masked = new LinkedHashMap<>(credentials);
        for (Map.Entry<String, Object> entry : masked.entrySet()) {
            if (SENSITIVE_CREDENTIAL_KEYS.contains(entry.getKey()) && entry.getValue() != null) {
                String value = String.valueOf(entry.getValue());
                if (value.length() > 4) {
                    entry.setValue(value.substring(0, 2) + "****" + value.substring(value.length() - 2));
                } else {
                    entry.setValue("****");
                }
            }
        }
        return masked;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record PendingCredentialUpdate(long accountId, Map<String, Object> credentials) {
    }

    private record RefreshResult(String warning) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiIdTokenClaims(
            String email,
            @com.fasterxml.jackson.annotation.JsonProperty("https://api.openai.com/auth")
            OpenAiAuthClaims openAiAuth
    ) {
        private OpenAiUserInfo toUserInfo() {
            if (openAiAuth == null) {
                return new OpenAiUserInfo(email, null, null, null, null);
            }
            String organizationId = null;
            if (openAiAuth.organizations != null && !openAiAuth.organizations.isEmpty()) {
                for (OpenAiOrganizationClaim organization : openAiAuth.organizations) {
                    if (organization != null && organization.isDefault()) {
                        organizationId = organization.id();
                        break;
                    }
                }
                if (organizationId == null) {
                    OpenAiOrganizationClaim first = openAiAuth.organizations.get(0);
                    if (first != null) {
                        organizationId = first.id();
                    }
                }
            }
            return new OpenAiUserInfo(
                    email,
                    openAiAuth.chatgptAccountId,
                    openAiAuth.chatgptUserId,
                    openAiAuth.chatgptPlanType,
                    organizationId
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiAuthClaims(
            @com.fasterxml.jackson.annotation.JsonProperty("chatgpt_account_id")
            String chatgptAccountId,
            @com.fasterxml.jackson.annotation.JsonProperty("chatgpt_user_id")
            String chatgptUserId,
            @com.fasterxml.jackson.annotation.JsonProperty("chatgpt_plan_type")
            String chatgptPlanType,
            List<OpenAiOrganizationClaim> organizations
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiOrganizationClaim(
            String id,
            @com.fasterxml.jackson.annotation.JsonProperty("is_default")
            boolean isDefault
    ) {
    }

    private record OpenAiUserInfo(
            String email,
            String chatgptAccountId,
            String chatgptUserId,
            String planType,
            String organizationId
    ) {
    }
}
