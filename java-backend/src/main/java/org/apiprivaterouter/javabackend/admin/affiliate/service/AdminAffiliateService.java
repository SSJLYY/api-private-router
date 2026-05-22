package org.apiprivaterouter.javabackend.admin.affiliate.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateAdminEntry;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateInviteRecord;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateRebateRecord;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateRecordFilter;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateTransferRecord;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateUserOverview;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateUserSummary;
import org.apiprivaterouter.javabackend.admin.affiliate.model.BatchSetRateRequest;
import org.apiprivaterouter.javabackend.admin.affiliate.model.UpdateAffiliateUserRequest;
import org.apiprivaterouter.javabackend.admin.affiliate.repository.AdminAffiliateRepository;
import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AdminAffiliateService {

    private static final String CODE_CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_MIN_LENGTH = 4;
    private static final int CODE_MAX_LENGTH = 32;
    private static final int CODE_RANDOM_LENGTH = 12;
    private static final int CODE_MAX_ATTEMPTS = 10;
    private static final double RATE_MIN = 0.0;
    private static final double RATE_MAX = 100.0;
    private static final double RATE_DEFAULT = 20.0;

    private final AdminAffiliateRepository repository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminAffiliateService(AdminAffiliateRepository repository, NamedParameterJdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResponse<AffiliateAdminEntry> listUsers(int page, int pageSize, String search) {
        return repository.listUsersWithCustomSettings(page, pageSize, search);
    }

    public List<AffiliateUserSummary> lookupUsers(String q) {
        return repository.lookupUsers(q);
    }

    @Transactional
    public Map<String, Long> updateUserSettings(long userId, UpdateAffiliateUserRequest request) {
        requireExistingUser(userId);
        repository.ensureUserAffiliate(userId);
        if (request.aff_code() != null) {
            updateAffiliateCode(userId, request.aff_code());
        }
        boolean clearRate = Boolean.TRUE.equals(request.clear_rebate_rate());
        if (clearRate) {
            repository.setUserRebateRate(userId, null);
        } else if (request.aff_rebate_rate_percent() != null) {
            repository.setUserRebateRate(userId, validateRate(request.aff_rebate_rate_percent()));
        }
        return Map.of("user_id", userId);
    }

    @Transactional
    public Map<String, Long> clearUserSettings(long userId) {
        requireExistingUser(userId);
        repository.ensureUserAffiliate(userId);
        repository.setUserRebateRate(userId, null);
        resetAffiliateCode(userId);
        return Map.of("user_id", userId);
    }

    @Transactional
    public Map<String, Integer> batchSetRate(BatchSetRateRequest request) {
        List<Long> userIds = normalizeUserIds(request.user_ids());
        if (userIds.isEmpty()) {
            throw new ApiErrorException(400, "BAD_REQUEST", "user_ids cannot be empty");
        }
        boolean clear = Boolean.TRUE.equals(request.clear());
        if (!clear && request.aff_rebate_rate_percent() == null) {
            throw new ApiErrorException(400, "BAD_REQUEST", "aff_rebate_rate_percent is required unless clear=true");
        }
        Double rate = clear ? null : validateRate(request.aff_rebate_rate_percent());
        for (Long userId : userIds) {
            requireExistingUser(userId);
            repository.ensureUserAffiliate(userId);
        }
        repository.batchSetUserRebateRate(userIds, rate);
        return Map.of("affected", userIds.size());
    }

    public AffiliateUserOverview getUserOverview(long userId) {
        requirePositiveUserId(userId);
        AdminAffiliateRepository.AffiliateUserOverviewRow row = repository.findUserOverviewRow(userId)
                .orElseThrow(() -> new ApiErrorException(404, "USER_NOT_FOUND", "user not found"));
        double effectiveRate = row.hasCustomRate() ? clampRate(row.customRate()) : readGlobalRebateRate();
        return row.toOverview(effectiveRate);
    }

    public PageResponse<AffiliateInviteRecord> listInviteRecords(AffiliateRecordFilter filter) {
        return repository.listInviteRecords(normalizeRecordFilter(filter));
    }

    public PageResponse<AffiliateRebateRecord> listRebateRecords(AffiliateRecordFilter filter) {
        return repository.listRebateRecords(normalizeRecordFilter(filter));
    }

    public PageResponse<AffiliateTransferRecord> listTransferRecords(AffiliateRecordFilter filter) {
        return repository.listTransferRecords(normalizeRecordFilter(filter));
    }

    private void updateAffiliateCode(long userId, String rawCode) {
        String code = normalizeCode(rawCode);
        if (!isValidCode(code)) {
            throw new StructuredApiErrorException(400, "AFFILIATE_CODE_INVALID", "invalid affiliate code");
        }
        try {
            repository.updateUserAffCode(userId, code);
        } catch (IllegalStateException ex) {
            if ("USER_NOT_FOUND".equals(ex.getMessage())) {
                throw new ApiErrorException(404, "USER_NOT_FOUND", "user not found");
            }
            throw ex;
        } catch (DataIntegrityViolationException ex) {
            throw new StructuredApiErrorException(409, "AFFILIATE_CODE_TAKEN", "affiliate code already in use");
        }
    }

    private void resetAffiliateCode(long userId) {
        for (int attempt = 0; attempt < CODE_MAX_ATTEMPTS; attempt++) {
            try {
                repository.resetUserAffCode(userId, generateCode());
                return;
            } catch (DataIntegrityViolationException ignored) {
            } catch (IllegalStateException ex) {
                if ("USER_NOT_FOUND".equals(ex.getMessage())) {
                    throw new ApiErrorException(404, "USER_NOT_FOUND", "user not found");
                }
                throw ex;
            }
        }
        throw new StructuredApiErrorException(500, "AFFILIATE_CODE_RESET_FAILED", "failed to reset affiliate code");
    }

    private Double validateRate(Double rate) {
        if (rate == null || rate.isNaN() || rate.isInfinite()) {
            throw new StructuredApiErrorException(400, "INVALID_RATE", "invalid rebate rate");
        }
        if (rate < RATE_MIN || rate > RATE_MAX) {
            throw new StructuredApiErrorException(400, "INVALID_RATE", "rebate rate out of range");
        }
        return rate;
    }

    private AffiliateRecordFilter normalizeRecordFilter(AffiliateRecordFilter filter) {
        int page = Math.max(filter.page(), 1);
        int pageSize = filter.page_size() <= 0 ? 20 : Math.min(filter.page_size(), 100);
        return new AffiliateRecordFilter(
                filter.search() == null ? "" : filter.search().trim(),
                page,
                pageSize,
                filter.start_at(),
                filter.end_at(),
                filter.sort_by() == null ? "" : filter.sort_by().trim(),
                filter.sort_desc()
        );
    }

    private List<Long> normalizeUserIds(List<Long> rawUserIds) {
        if (rawUserIds == null || rawUserIds.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Long userId : rawUserIds) {
            if (userId != null && userId > 0) {
                result.add(userId);
            }
        }
        return result;
    }

    private void requireExistingUser(long userId) {
        requirePositiveUserId(userId);
        if (!repository.existsUser(userId)) {
            throw new ApiErrorException(404, "USER_NOT_FOUND", "user not found");
        }
    }

    private void requirePositiveUserId(long userId) {
        if (userId <= 0) {
            throw new ApiErrorException(400, "INVALID_USER", "invalid user");
        }
    }

    private String normalizeCode(String rawCode) {
        return rawCode == null ? "" : rawCode.trim().toUpperCase();
    }

    private boolean isValidCode(String code) {
        if (code.length() < CODE_MIN_LENGTH || code.length() > CODE_MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            boolean ok = ch >= 'A' && ch <= 'Z'
                    || ch >= '0' && ch <= '9'
                    || ch == '_'
                    || ch == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private String generateCode() {
        StringBuilder builder = new StringBuilder(CODE_RANDOM_LENGTH);
        for (int i = 0; i < CODE_RANDOM_LENGTH; i++) {
            builder.append(CODE_CHARSET.charAt(secureRandom.nextInt(CODE_CHARSET.length())));
        }
        return builder.toString();
    }

    private double readGlobalRebateRate() {
        String raw = jdbcTemplate.query("""
                select value
                from settings
                where key = :key
                limit 1
                """, new MapSqlParameterSource("key", "affiliate_rebate_rate"),
                rs -> rs.next() ? rs.getString("value") : null);
        if (raw == null || raw.isBlank()) {
            return RATE_DEFAULT;
        }
        try {
            return clampRate(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException ex) {
            return RATE_DEFAULT;
        }
    }

    private double clampRate(double rate) {
        if (Double.isNaN(rate) || Double.isInfinite(rate)) {
            return RATE_DEFAULT;
        }
        if (rate < RATE_MIN) {
            return RATE_MIN;
        }
        if (rate > RATE_MAX) {
            return RATE_MAX;
        }
        return rate;
    }
}
