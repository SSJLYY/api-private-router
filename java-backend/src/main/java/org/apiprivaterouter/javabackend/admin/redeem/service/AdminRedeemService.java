package org.apiprivaterouter.javabackend.admin.redeem.service;

import org.apiprivaterouter.javabackend.admin.redeem.model.AdminRedeemCodeResponse;
import org.apiprivaterouter.javabackend.admin.redeem.model.CreateAndRedeemCodeRequest;
import org.apiprivaterouter.javabackend.admin.redeem.model.CreateAndRedeemCodeResponse;
import org.apiprivaterouter.javabackend.admin.redeem.model.GenerateRedeemCodesRequest;
import org.apiprivaterouter.javabackend.admin.redeem.model.RedeemStatsResponse;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.admin.redeem.repository.AdminRedeemRepository;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminRedeemService {

    private static final List<String> ALLOWED_TYPES = List.of("balance", "concurrency", "subscription", "invitation");
    private static final List<String> ALLOWED_STATUSES = List.of("active", "used", "expired", "unused");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminRedeemRepository repository;

    public AdminRedeemService(AdminRedeemRepository repository) {
        this.repository = repository;
    }

    public PageResponse<AdminRedeemCodeResponse> listCodes(int page, int pageSize, String type, String status, String search, String sortBy, String sortOrder) {
        return repository.listCodes(page, pageSize, normalizeType(type, true), normalizeStatus(status, true), trimSearch(search), sortBy, sortOrder);
    }

    public AdminRedeemCodeResponse getCode(long id) {
        return repository.getCode(id).orElseThrow(() -> new IllegalArgumentException("redeem code not found"));
    }

    @Transactional
    public List<AdminRedeemCodeResponse> generateCodes(GenerateRedeemCodesRequest request) {
        String type = normalizeType(request.type(), false);
        double value = request.value();
        if (!"invitation".equals(type) && value == 0) {
            throw new IllegalArgumentException("value must not be zero");
        }
        Long groupId = request.group_id();
        int validityDays = request.validity_days() == null || request.validity_days() <= 0 ? 30 : request.validity_days();
        if ("subscription".equals(type)) {
            if (groupId == null || groupId <= 0) {
                throw new IllegalArgumentException("group_id is required for subscription type");
            }
            if (!repository.existsSubscriptionGroup(groupId)) {
                throw new IllegalArgumentException("group must be subscription type");
            }
        } else {
            groupId = null;
            validityDays = 30;
        }
        double storedValue = "invitation".equals(type) ? 0 : value;
        int count = request.count();
        Long finalGroupId = groupId;
        int finalValidityDays = validityDays;
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> {
                    long id = repository.createCode(generateCode(), type, storedValue, "unused", finalGroupId, finalValidityDays);
                    return repository.getCode(id).orElseThrow(() -> new IllegalArgumentException("redeem code not found"));
                })
                .toList();
    }

    public Map<String, String> deleteCode(long id) {
        repository.deleteCode(id);
        return Map.of("message", "Redeem code deleted successfully");
    }

    @Transactional
    public CreateAndRedeemCodeResponse createAndRedeem(CreateAndRedeemCodeRequest request) {
        String code = normalizeCode(request.code());
        String type = request.type() == null || request.type().isBlank()
                ? "balance"
                : normalizeType(request.type(), false);
        double value = request.value() == null ? 0D : request.value();
        long userId = request.userId();
        if (!repository.userExists(userId)) {
            throw new IllegalArgumentException("user not found");
        }
        Long groupId = request.groupId();
        int validityDays = request.validityDays() == null ? 0 : request.validityDays();
        if ("subscription".equals(type)) {
            if (groupId == null || groupId <= 0) {
                throw new IllegalArgumentException("group_id is required for subscription type");
            }
            if (validityDays == 0) {
                throw new IllegalArgumentException("validity_days must not be zero for subscription type");
            }
            if (!repository.existsSubscriptionGroup(groupId)) {
                throw new IllegalArgumentException("group must be subscription type");
            }
        } else {
            groupId = null;
            validityDays = 30;
        }

        AdminRedeemCodeResponse existing = repository.findCodeByCodeForUpdate(code).orElse(null);
        if (existing != null) {
            return resolveExistingAndMaybeRedeem(existing, userId);
        }

        long id = repository.createCode(code, type, value, "unused", groupId, validityDays, request.notes());
        AdminRedeemCodeResponse created = repository.getCode(id)
                .orElseThrow(() -> new IllegalStateException("redeem code not found"));
        return redeemForUser(created, userId);
    }

    public Map<String, Object> batchDelete(List<Long> ids) {
        long deleted = 0;
        for (Long id : ids) {
            if (id == null || id <= 0) {
                continue;
            }
            try {
                repository.deleteCode(id);
                deleted++;
            } catch (Exception ignored) {
            }
        }
        return Map.of(
                "deleted", deleted,
                "message", "Redeem codes deleted successfully"
        );
    }

    @Transactional
    public AdminRedeemCodeResponse expireCode(long id) {
        repository.getCode(id).orElseThrow(() -> new IllegalArgumentException("redeem code not found"));
        repository.updateStatus(id, "expired");
        return repository.getCode(id).orElseThrow(() -> new IllegalArgumentException("redeem code not found"));
    }

    public RedeemStatsResponse getStats() {
        RedeemStatsResponse stats = repository.getStats();
        Map<String, Long> normalizedByType = new LinkedHashMap<>();
        normalizedByType.put("balance", stats.by_type().getOrDefault("balance", 0L));
        normalizedByType.put("concurrency", stats.by_type().getOrDefault("concurrency", 0L));
        normalizedByType.put("subscription", stats.by_type().getOrDefault("subscription", 0L));
        normalizedByType.put("invitation", stats.by_type().getOrDefault("invitation", 0L));
        return new RedeemStatsResponse(
                stats.total_codes(),
                stats.active_codes(),
                stats.used_codes(),
                stats.expired_codes(),
                stats.total_value_distributed(),
                normalizedByType
        );
    }

    public String exportCodes(String type, String status, String search, String sortBy, String sortOrder) {
        return repository.exportCodesCsv(normalizeType(type, true), normalizeStatus(status, true), trimSearch(search), sortBy, sortOrder);
    }

    private CreateAndRedeemCodeResponse resolveExistingAndMaybeRedeem(AdminRedeemCodeResponse existing, long userId) {
        if ("unused".equalsIgnoreCase(existing.status())) {
            return redeemForUser(existing, userId);
        }
        if (existing.used_by() != null && existing.used_by() == userId) {
            return new CreateAndRedeemCodeResponse(existing);
        }
        throw new StructuredApiErrorException(409, "REDEEM_CODE_CONFLICT", "redeem code already used by another user");
    }

    private CreateAndRedeemCodeResponse redeemForUser(AdminRedeemCodeResponse code, long userId) {
        if (repository.markCodeUsed(code.id(), userId) != 1) {
            AdminRedeemCodeResponse latest = repository.findCodeByCodeForUpdate(code.code()).orElse(code);
            return resolveExistingAndMaybeRedeem(latest, userId);
        }
        applyRedeemEffect(code, userId);
        AdminRedeemCodeResponse updated = repository.getCode(code.id()).orElse(code);
        return new CreateAndRedeemCodeResponse(updated);
    }

    private void applyRedeemEffect(AdminRedeemCodeResponse code, long userId) {
        switch (code.type()) {
            case "balance" -> repository.addBalance(userId, code.value());
            case "concurrency" -> repository.addConcurrency(userId, (int) code.value());
            case "subscription" -> applySubscriptionRedeem(code, userId);
            case "invitation" -> {
                return;
            }
            default -> throw new IllegalArgumentException("unsupported redeem type: " + code.type());
        }
    }

    private void applySubscriptionRedeem(AdminRedeemCodeResponse code, long userId) {
        if (code.group_id() == null) {
            throw new IllegalArgumentException("invalid subscription redeem code: missing group_id");
        }
        int validityDays = code.validity_days();
        if (validityDays == 0) {
            throw new IllegalArgumentException("validity_days must not be zero for subscription type");
        }
        OffsetDateTime now = OffsetDateTime.now();
        String note = "Redeemed via code " + code.code();
        AdminRedeemRepository.SubscriptionSnapshot existing = repository.findLatestSubscriptionForUpdate(userId, code.group_id()).orElse(null);
        if (existing == null) {
            repository.createSubscription(
                    userId,
                    code.group_id(),
                    now,
                    now.plusDays(validityDays),
                    note
            );
            return;
        }
        OffsetDateTime base = existing.expiresAt() != null && existing.expiresAt().isAfter(now) ? existing.expiresAt() : now;
        String notes = existing.notes() == null || existing.notes().isBlank() ? note : existing.notes() + "\n" + note;
        repository.updateSubscription(existing.id(), base.plusDays(validityDays), "active", notes);
    }

    private String normalizeType(String type, boolean patch) {
        if (type == null || type.trim().isEmpty()) {
            return patch ? null : "balance";
        }
        String normalized = type.trim().toLowerCase();
        if (!ALLOWED_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("type is invalid");
        }
        return normalized;
    }

    private String normalizeStatus(String status, boolean patch) {
        if (status == null || status.trim().isEmpty()) {
            return patch ? null : "unused";
        }
        String normalized = status.trim().toLowerCase();
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status is invalid");
        }
        return normalized;
    }

    private String trimSearch(String search) {
        if (search == null) {
            return null;
        }
        String trimmed = search.trim();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    private String generateCode() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte value : bytes) {
            hex.append(String.format("%02X", value));
        }
        return hex.substring(0, 8) + "-" +
                hex.substring(8, 16) + "-" +
                hex.substring(16, 24) + "-" +
                hex.substring(24, 32);
    }

    private String normalizeCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("code is required");
        }
        return code.trim();
    }

    @Transactional
    public Map<String, Object> batchUpdate(List<Long> ids, Map<String, Object> fields) {
        if (ids == null || ids.isEmpty()) {
            throw new StructuredApiErrorException(400, "REDEEM_CODE_BATCH_UPDATE_IDS_REQUIRED", "ids are required");
        }
        if (fields == null || fields.isEmpty()) {
            throw new StructuredApiErrorException(400, "REDEEM_CODE_BATCH_UPDATE_EMPTY", "at least one field must be selected");
        }
        if (fields.containsKey("type") || fields.containsKey("value")) {
            throw new StructuredApiErrorException(400, "REDEEM_CODE_CORE_FIELDS_IMMUTABLE", "type and value cannot be batch updated");
        }
        List<Long> distinctIds = ids.stream().distinct().filter(id -> id > 0).toList();
        if (distinctIds.isEmpty()) {
            throw new StructuredApiErrorException(400, "REDEEM_CODE_BATCH_UPDATE_INVALID_ID", "ids must be positive");
        }
        String status = fields.get("status") instanceof String s ? s : null;
        if (status != null && !List.of("unused", "disabled").contains(status.toLowerCase())) {
            throw new StructuredApiErrorException(400, "REDEEM_CODE_STATUS_INVALID", "status must be unused or disabled");
        }
        String expiresAt = fields.get("expires_at") instanceof String s ? s : null;
        if (expiresAt != null) {
            try {
                OffsetDateTime parsed = OffsetDateTime.parse(expiresAt);
                if (!parsed.isAfter(OffsetDateTime.now())) {
                    throw new StructuredApiErrorException(400, "REDEEM_CODE_EXPIRES_AT_INVALID", "expires_at must be in the future");
                }
            } catch (StructuredApiErrorException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new StructuredApiErrorException(400, "REDEEM_CODE_EXPIRES_AT_INVALID", "expires_at must be a valid ISO datetime");
            }
        }
        Long groupId = fields.get("group_id") instanceof Number n ? n.longValue() : null;
        if (fields.containsKey("group_id") && groupId != null && groupId <= 0) {
            throw new StructuredApiErrorException(400, "REDEEM_CODE_GROUP_ID_INVALID", "group_id must be positive");
        }
        String notes = fields.get("notes") instanceof String s ? s : null;
        int updated = repository.batchUpdateCodes(distinctIds, status, expiresAt, notes, groupId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", updated);
        result.put("message", "Redeem codes updated successfully");
        return result;
    }
}
