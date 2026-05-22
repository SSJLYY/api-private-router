package org.apiprivaterouter.javabackend.admin.promo.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.promo.model.AdminPromoCodeResponse;
import org.apiprivaterouter.javabackend.admin.promo.model.AdminPromoCodeUsageResponse;
import org.apiprivaterouter.javabackend.admin.promo.model.CreatePromoCodeRequest;
import org.apiprivaterouter.javabackend.admin.promo.model.UpdatePromoCodeRequest;
import org.apiprivaterouter.javabackend.admin.promo.service.AdminPromoService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/promo-codes")
public class AdminPromoController {

    private final AdminPromoService service;
    private final CurrentUserContext currentUserContext;

    public AdminPromoController(AdminPromoService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminPromoCodeResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listCodes(page, pageSize, status, search, sortBy, sortOrder));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminPromoCodeResponse> getById(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getCode(id));
    }

    @PostMapping
    public ApiResponse<AdminPromoCodeResponse> create(@Valid @RequestBody CreatePromoCodeRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.createCode(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminPromoCodeResponse> update(@PathVariable long id, @Valid @RequestBody UpdatePromoCodeRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateCode(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.deleteCode(id));
    }

    @GetMapping("/{id}/usages")
    public ApiResponse<PageResponse<AdminPromoCodeUsageResponse>> getUsages(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getUsages(id, page, pageSize));
    }
}
