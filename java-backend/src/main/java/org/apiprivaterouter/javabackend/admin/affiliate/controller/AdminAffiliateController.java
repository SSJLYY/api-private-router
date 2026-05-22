package org.apiprivaterouter.javabackend.admin.affiliate.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateAdminEntry;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateInviteRecord;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateRebateRecord;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateRecordFilter;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateTransferRecord;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateUserOverview;
import org.apiprivaterouter.javabackend.admin.affiliate.model.AffiliateUserSummary;
import org.apiprivaterouter.javabackend.admin.affiliate.model.BatchSetRateRequest;
import org.apiprivaterouter.javabackend.admin.affiliate.model.UpdateAffiliateUserRequest;
import org.apiprivaterouter.javabackend.admin.affiliate.service.AdminAffiliateService;
import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/affiliates")
public class AdminAffiliateController {

    private final AdminAffiliateService service;
    private final CurrentUserContext currentUserContext;

    public AdminAffiliateController(AdminAffiliateService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/users")
    public ApiResponse<PageResponse<AffiliateAdminEntry>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String search
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listUsers(page, pageSize, search));
    }

    @GetMapping("/users/lookup")
    public ApiResponse<List<AffiliateUserSummary>> lookupUsers(@RequestParam(name = "q", required = false) String q) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.lookupUsers(q));
    }

    @PutMapping("/users/{userId}")
    public ApiResponse<Map<String, Long>> updateUserSettings(
            @PathVariable("userId") long userId,
            @RequestBody UpdateAffiliateUserRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateUserSettings(userId, request));
    }

    @DeleteMapping("/users/{userId}")
    public ApiResponse<Map<String, Long>> clearUserSettings(@PathVariable("userId") long userId) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.clearUserSettings(userId));
    }

    @PostMapping("/users/batch-rate")
    public ApiResponse<Map<String, Integer>> batchSetRate(@RequestBody BatchSetRateRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.batchSetRate(request));
    }

    @GetMapping("/users/{userId}/overview")
    public ApiResponse<AffiliateUserOverview> getUserOverview(@PathVariable("userId") long userId) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getUserOverview(userId));
    }

    @GetMapping("/invites")
    public ApiResponse<PageResponse<AffiliateInviteRecord>> listInvites(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(name = "start_at", required = false) String startAt,
            @RequestParam(name = "end_at", required = false) String endAt,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listInviteRecords(buildFilter(page, pageSize, search, startAt, endAt, sortBy, sortOrder, timezone)));
    }

    @GetMapping("/rebates")
    public ApiResponse<PageResponse<AffiliateRebateRecord>> listRebates(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(name = "start_at", required = false) String startAt,
            @RequestParam(name = "end_at", required = false) String endAt,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listRebateRecords(buildFilter(page, pageSize, search, startAt, endAt, sortBy, sortOrder, timezone)));
    }

    @GetMapping("/transfers")
    public ApiResponse<PageResponse<AffiliateTransferRecord>> listTransfers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(name = "start_at", required = false) String startAt,
            @RequestParam(name = "end_at", required = false) String endAt,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder,
            @RequestParam(required = false) String timezone
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listTransferRecords(buildFilter(page, pageSize, search, startAt, endAt, sortBy, sortOrder, timezone)));
    }

    private AffiliateRecordFilter buildFilter(
            int page,
            int pageSize,
            String search,
            String startAt,
            String endAt,
            String sortBy,
            String sortOrder,
            String timezone
    ) {
        return new AffiliateRecordFilter(
                search,
                page,
                pageSize,
                parseStartAt(startAt, timezone),
                parseEndAt(endAt, timezone),
                sortBy,
                !"asc".equalsIgnoreCase(sortOrder)
        );
    }

    private OffsetDateTime parseStartAt(String raw, String timezone) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (Exception ignored) {
        }
        try {
            ZoneId zoneId = resolveZoneId(timezone);
            return LocalDate.parse(raw.trim()).atStartOfDay(zoneId).toOffsetDateTime();
        } catch (Exception ignored) {
            return null;
        }
    }

    private OffsetDateTime parseEndAt(String raw, String timezone) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim());
        } catch (Exception ignored) {
        }
        try {
            ZoneId zoneId = resolveZoneId(timezone);
            return LocalDate.parse(raw.trim()).plusDays(1).atStartOfDay(zoneId).minusNanos(1).toOffsetDateTime();
        } catch (Exception ignored) {
            return null;
        }
    }

    private ZoneId resolveZoneId(String timezone) {
        try {
            return timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
        } catch (Exception ex) {
            throw new ApiErrorException(400, "INVALID_TIMEZONE", "invalid timezone");
        }
    }
}
