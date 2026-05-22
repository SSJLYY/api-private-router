package org.apiprivaterouter.javabackend.userkeys.service;

import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.usergroups.service.UserGroupService;
import org.apiprivaterouter.javabackend.userkeys.model.CreateUserApiKeyRequest;
import org.apiprivaterouter.javabackend.userkeys.model.UpdateUserApiKeyRequest;
import org.apiprivaterouter.javabackend.userkeys.model.UserApiKeyResponse;
import org.apiprivaterouter.javabackend.userkeys.repository.UserApiKeyRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final UserApiKeyRepository repository;
    private final UserGroupService userGroupService;
    private final JsonHelper jsonHelper;

    public UserApiKeyService(UserApiKeyRepository repository, UserGroupService userGroupService, JsonHelper jsonHelper) {
        this.repository = repository;
        this.userGroupService = userGroupService;
        this.jsonHelper = jsonHelper;
    }

    public PageResponse<UserApiKeyResponse> list(
            CurrentUser currentUser,
            int page,
            int pageSize,
            String search,
            String status,
            Long groupId,
            String sortBy,
            String sortOrder
    ) {
        return repository.listByUser(currentUser.userId(), page, pageSize, search, normalizeFilterStatus(status), groupId, sortBy, sortOrder);
    }

    public UserApiKeyResponse getById(CurrentUser currentUser, long id) {
        return repository.findByIdForUser(id, currentUser.userId())
                .orElseThrow(() -> new HttpStatusException(404, "api key not found"));
    }

    public UserApiKeyResponse create(CurrentUser currentUser, CreateUserApiKeyRequest request) {
        String name = normalizeRequiredName(request.name());
        Long groupId = normalizeGroupId(request.group_id());
        validateGroupAccess(currentUser, groupId);
        List<String> ipWhitelist = normalizeIpList(request.ip_whitelist());
        List<String> ipBlacklist = normalizeIpList(request.ip_blacklist());
        String key = resolveKey(request.custom_key());
        double quota = normalizeNonNegative(request.quota(), "quota");
        Integer expiresInDays = request.expires_in_days();
        Timestamp expiresAt = expiresInDays == null || expiresInDays <= 0
                ? null
                : Timestamp.from(Instant.now().plusSeconds(expiresInDays.longValue() * 24L * 60L * 60L));
        double rateLimit5h = normalizeNonNegative(request.rate_limit_5h(), "rate_limit_5h");
        double rateLimit1d = normalizeNonNegative(request.rate_limit_1d(), "rate_limit_1d");
        double rateLimit7d = normalizeNonNegative(request.rate_limit_7d(), "rate_limit_7d");
        long id = repository.create(new UserApiKeyRepository.CreateCommand(
                currentUser.userId(),
                key,
                name,
                groupId,
                "active",
                jsonHelper.writeJson(ipWhitelist),
                jsonHelper.writeJson(ipBlacklist),
                quota,
                0,
                expiresAt,
                rateLimit5h,
                rateLimit1d,
                rateLimit7d,
                0,
                0,
                0,
                null,
                null,
                null
        ));
        return getById(currentUser, id);
    }

    public UserApiKeyResponse update(CurrentUser currentUser, long id, UpdateUserApiKeyRequest request) {
        UserApiKeyResponse current = getById(currentUser, id);
        String name = current.name();
        Long groupId = current.group_id();
        String status = toStoredStatus(current.status());
        List<String> ipWhitelist = current.ip_whitelist();
        List<String> ipBlacklist = current.ip_blacklist();
        double quota = current.quota();
        double quotaUsed = current.quota_used();
        Timestamp expiresAt = parseTimestamp(current.expires_at());
        double rateLimit5h = current.rate_limit_5h();
        double rateLimit1d = current.rate_limit_1d();
        double rateLimit7d = current.rate_limit_7d();
        double usage5h = current.usage_5h();
        double usage1d = current.usage_1d();
        double usage7d = current.usage_7d();
        Timestamp window5hStart = parseTimestamp(current.window_5h_start());
        Timestamp window1dStart = parseTimestamp(current.window_1d_start());
        Timestamp window7dStart = parseTimestamp(current.window_7d_start());

        if (request.isNamePresent()) {
            name = normalizeRequiredName(request.getName());
        }
        if (request.isGroupIdPresent()) {
            groupId = normalizeGroupId(request.getGroupId());
            validateGroupAccess(currentUser, groupId);
        }
        if (request.isStatusPresent()) {
            status = toStoredStatus(normalizeWritableStatus(request.getStatus()));
        }
        if (request.isIpWhitelistPresent()) {
            ipWhitelist = normalizeIpList(request.getIpWhitelist());
        }
        if (request.isIpBlacklistPresent()) {
            ipBlacklist = normalizeIpList(request.getIpBlacklist());
        }
        if (request.isQuotaPresent()) {
            quota = normalizeNonNegative(request.getQuota(), "quota");
            if ("quota_exhausted".equals(status) && quota > quotaUsed) {
                status = "active";
            }
        }
        if (request.isResetQuotaPresent() && Boolean.TRUE.equals(request.getResetQuota())) {
            quotaUsed = 0;
            if ("quota_exhausted".equals(status)) {
                status = "active";
            }
        }
        if (request.isExpiresAtPresent()) {
            String raw = request.getExpiresAt();
            if (raw == null || raw.isBlank()) {
                expiresAt = null;
                if ("expired".equals(status)) {
                    status = "active";
                }
            } else {
                expiresAt = Timestamp.from(OffsetDateTime.parse(raw.trim()).toInstant());
                if ("expired".equals(status) && expiresAt.toInstant().isAfter(Instant.now())) {
                    status = "active";
                }
            }
        }
        if (request.isRateLimit5hPresent()) {
            rateLimit5h = normalizeNonNegative(request.getRateLimit5h(), "rate_limit_5h");
        }
        if (request.isRateLimit1dPresent()) {
            rateLimit1d = normalizeNonNegative(request.getRateLimit1d(), "rate_limit_1d");
        }
        if (request.isRateLimit7dPresent()) {
            rateLimit7d = normalizeNonNegative(request.getRateLimit7d(), "rate_limit_7d");
        }
        if (request.isResetRateLimitUsagePresent() && Boolean.TRUE.equals(request.getResetRateLimitUsage())) {
            usage5h = 0;
            usage1d = 0;
            usage7d = 0;
            window5hStart = null;
            window1dStart = null;
            window7dStart = null;
        }

        boolean updated = repository.update(new UserApiKeyRepository.UpdateCommand(
                id,
                currentUser.userId(),
                current.key(),
                name,
                groupId,
                status,
                jsonHelper.writeJson(ipWhitelist),
                jsonHelper.writeJson(ipBlacklist),
                quota,
                quotaUsed,
                expiresAt,
                rateLimit5h,
                rateLimit1d,
                rateLimit7d,
                usage5h,
                usage1d,
                usage7d,
                window5hStart,
                window1dStart,
                window7dStart
        ));
        if (!updated) {
            throw new HttpStatusException(404, "api key not found");
        }
        return getById(currentUser, id);
    }

    public Map<String, String> delete(CurrentUser currentUser, long id) {
        UserApiKeyResponse current = getById(currentUser, id);
        String tombstoneKey = "__deleted__" + id + "__" + System.nanoTime();
        boolean deleted = repository.softDelete(id, currentUser.userId(), tombstoneKey);
        if (!deleted) {
            throw new HttpStatusException(404, "api key not found");
        }
        return Map.of("message", "API key deleted successfully");
    }

    private String resolveKey(String customKey) {
        String normalizedCustomKey = normalizeOptionalText(customKey);
        if (normalizedCustomKey != null) {
            validateCustomKey(normalizedCustomKey);
            if (repository.existsActiveKey(normalizedCustomKey)) {
                throw new HttpStatusException(409, "api key already exists");
            }
            return normalizedCustomKey;
        }
        for (int i = 0; i < 5; i++) {
            String generated = generateRandomKey();
            if (!repository.existsActiveKey(generated)) {
                return generated;
            }
        }
        throw new IllegalStateException("failed to generate unique api key");
    }

    private String generateRandomKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "sk-" + HEX.formatHex(bytes);
    }

    private void validateCustomKey(String key) {
        if (key.length() < 16) {
            throw new IllegalArgumentException("api key must be at least 16 characters");
        }
        if (!key.matches("^[a-zA-Z0-9_-]+$")) {
            throw new IllegalArgumentException("api key can only contain letters, numbers, underscores, and hyphens");
        }
    }

    private void validateGroupAccess(CurrentUser currentUser, Long groupId) {
        if (groupId == null) {
            return;
        }
        Set<Long> allowed = userGroupService.getAvailableGroups(currentUser).stream()
                .map(group -> group.id())
                .collect(Collectors.toSet());
        if (!allowed.contains(groupId)) {
            throw new HttpStatusException(403, "user is not allowed to bind this group");
        }
    }

    private String normalizeRequiredName(String name) {
        String normalized = normalizeOptionalText(name);
        if (normalized == null) {
            throw new IllegalArgumentException("name is required");
        }
        return normalized;
    }

    private Long normalizeGroupId(Long groupId) {
        return groupId == null || groupId <= 0 ? null : groupId;
    }

    private List<String> normalizeIpList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(this::normalizeOptionalText)
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private double normalizeNonNegative(Double value, String field) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be >= 0");
        }
        return value;
    }

    private String normalizeWritableStatus(String status) {
        String normalized = normalizeOptionalText(status);
        if (normalized == null) {
            throw new IllegalArgumentException("status is required");
        }
        normalized = normalized.toLowerCase();
        if (!Set.of("active", "inactive", "quota_exhausted", "expired").contains(normalized)) {
            throw new IllegalArgumentException("status is invalid");
        }
        return normalized;
    }

    private String normalizeFilterStatus(String status) {
        String normalized = normalizeOptionalText(status);
        return normalized == null ? null : normalized.toLowerCase();
    }

    private String toStoredStatus(String status) {
        return "inactive".equalsIgnoreCase(status) ? "disabled" : status;
    }

    private String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Timestamp parseTimestamp(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        return Timestamp.from(Instant.parse(iso));
    }
}
