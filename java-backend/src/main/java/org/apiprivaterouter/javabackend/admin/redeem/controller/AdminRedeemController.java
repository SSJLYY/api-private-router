package org.apiprivaterouter.javabackend.admin.redeem.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.redeem.model.AdminRedeemCodeResponse;
import org.apiprivaterouter.javabackend.admin.redeem.model.BatchDeleteRedeemCodesRequest;
import org.apiprivaterouter.javabackend.admin.redeem.model.CreateAndRedeemCodeRequest;
import org.apiprivaterouter.javabackend.admin.redeem.model.CreateAndRedeemCodeResponse;
import org.apiprivaterouter.javabackend.admin.redeem.model.GenerateRedeemCodesRequest;
import org.apiprivaterouter.javabackend.admin.redeem.model.RedeemStatsResponse;
import org.apiprivaterouter.javabackend.admin.redeem.service.AdminRedeemService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/redeem-codes")
public class AdminRedeemController {

    private final AdminRedeemService service;
    private final CurrentUserContext currentUserContext;

    public AdminRedeemController(AdminRedeemService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminRedeemCodeResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listCodes(page, pageSize, type, status, search, sortBy, sortOrder));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminRedeemCodeResponse> getById(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getCode(id));
    }

    @PostMapping("/generate")
    public ApiResponse<List<AdminRedeemCodeResponse>> generate(@Valid @RequestBody GenerateRedeemCodesRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.generateCodes(request));
    }

    @PostMapping("/create-and-redeem")
    public ApiResponse<CreateAndRedeemCodeResponse> createAndRedeem(@Valid @RequestBody CreateAndRedeemCodeRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.createAndRedeem(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.deleteCode(id));
    }

    @PostMapping("/batch-delete")
    public ApiResponse<Map<String, Object>> batchDelete(@Valid @RequestBody BatchDeleteRedeemCodesRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.batchDelete(request.ids()));
    }

    @PostMapping("/batch-update")
    public ApiResponse<Map<String, Object>> batchUpdate(@RequestBody Map<String, Object> request) {
        currentUserContext.requireAdmin();
        @SuppressWarnings("unchecked")
        java.util.List<Long> ids = request.get("ids") instanceof java.util.List<?> l
                ? l.stream().map(v -> v instanceof Number n ? n.longValue() : 0L).filter(v -> v > 0).toList()
                : java.util.List.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = request.get("fields") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        return ApiResponse.success(service.batchUpdate(ids, fields));
    }

    @PostMapping("/{id}/expire")
    public ApiResponse<AdminRedeemCodeResponse> expire(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.expireCode(id));
    }

    @GetMapping("/stats")
    public ApiResponse<RedeemStatsResponse> getStats() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getStats());
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        String csv = service.exportCodes(type, status, search, sortBy, sortOrder);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=redeem_codes.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
