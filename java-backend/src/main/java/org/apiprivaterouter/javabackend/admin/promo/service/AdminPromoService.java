package org.apiprivaterouter.javabackend.admin.promo.service;

import org.apiprivaterouter.javabackend.admin.promo.model.AdminPromoCodeResponse;
import org.apiprivaterouter.javabackend.admin.promo.model.AdminPromoCodeUsageResponse;
import org.apiprivaterouter.javabackend.admin.promo.model.CreatePromoCodeRequest;
import org.apiprivaterouter.javabackend.admin.promo.model.UpdatePromoCodeRequest;
import org.apiprivaterouter.javabackend.admin.promo.repository.AdminPromoRepository;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

@Service
public class AdminPromoService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final AdminPromoRepository repository;

    public AdminPromoService(AdminPromoRepository repository) {
        this.repository = repository;
    }

    public PageResponse<AdminPromoCodeResponse> listCodes(int page, int pageSize, String status, String search, String sortBy, String sortOrder) {
        return repository.listCodes(Math.max(page, 1), normalizePageSize(pageSize), status, search, sortBy, sortOrder);
    }

    public AdminPromoCodeResponse getCode(long id) {
        return repository.getCode(id).orElseThrow(() -> new HttpStatusException(404, "promo code not found"));
    }

    public AdminPromoCodeResponse createCode(CreatePromoCodeRequest request) {
        try {
            long id = repository.createCode(
                    normalizeCode(request.code()),
                    request.bonus_amount(),
                    request.max_uses() == null ? 0 : Math.max(request.max_uses(), 0),
                    toExpiresAt(request.expires_at()),
                    request.notes()
            );
            return getCode(id);
        } catch (DataIntegrityViolationException ex) {
            throw new HttpStatusException(409, "promo code already exists");
        }
    }

    public AdminPromoCodeResponse updateCode(long id, UpdatePromoCodeRequest request) {
        AdminPromoCodeResponse current = getCode(id);
        try {
            repository.updateCode(
                    id,
                    request.code() == null ? current.code() : normalizeCode(request.code()),
                    request.bonus_amount() == null ? current.bonus_amount() : request.bonus_amount(),
                    request.max_uses() == null ? current.max_uses() : Math.max(request.max_uses(), 0),
                    request.status() == null || request.status().isBlank() ? current.status() : request.status().trim(),
                    request.expires_at() == null ? parseInstant(current.expires_at()) : toExpiresAt(request.expires_at()),
                    request.notes() == null ? current.notes() : request.notes()
            );
            return getCode(id);
        } catch (DataIntegrityViolationException ex) {
            throw new HttpStatusException(409, "promo code already exists");
        }
    }

    public Map<String, String> deleteCode(long id) {
        getCode(id);
        repository.deleteCode(id);
        return Map.of("message", "Promo code deleted successfully");
    }

    public PageResponse<AdminPromoCodeUsageResponse> getUsages(long id, int page, int pageSize) {
        getCode(id);
        return repository.listUsages(id, Math.max(page, 1), normalizePageSize(pageSize));
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 200);
    }

    private String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim();
        if (normalized.isEmpty()) {
            byte[] bytes = new byte[8];
            RANDOM.nextBytes(bytes);
            return HexFormat.of().formatHex(bytes).toUpperCase();
        }
        return normalized.toUpperCase();
    }

    private Instant toExpiresAt(Long unixSeconds) {
        if (unixSeconds == null || unixSeconds == 0L) {
            return null;
        }
        return Instant.ofEpochSecond(unixSeconds);
    }

    private Instant parseInstant(String raw) {
        return raw == null || raw.isBlank() ? null : Instant.parse(raw);
    }
}
