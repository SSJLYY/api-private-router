package org.apiprivaterouter.javabackend.admin.proxy.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminDataImportResult;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminDataPayload;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.BatchCreateProxiesRequest;
import org.apiprivaterouter.javabackend.admin.proxy.model.BatchCreateProxiesResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.BatchDeleteProxiesRequest;
import org.apiprivaterouter.javabackend.admin.proxy.model.BatchDeleteProxiesResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.CreateProxyRequest;
import org.apiprivaterouter.javabackend.admin.proxy.model.ProxyAccountSummaryResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.ProxyDataImportRequest;
import org.apiprivaterouter.javabackend.admin.proxy.model.ProxyQualityCheckResultResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.ProxyStatsResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.TestProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.UpdateProxyRequest;
import org.apiprivaterouter.javabackend.admin.proxy.service.AdminProxyService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/proxies")
public class AdminProxyController {

    private static final Logger log = LoggerFactory.getLogger(AdminProxyController.class);

    private final AdminProxyService service;
    private final CurrentUserContext currentUserContext;

    public AdminProxyController(AdminProxyService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminProxyResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listProxies(page, pageSize, protocol, status, search, sortBy, sortOrder));
    }

    @GetMapping("/all")
    public ApiResponse<List<AdminProxyResponse>> getAll(
            @RequestParam(required = false) String protocol,
            @RequestParam(name = "with_count", defaultValue = "false") boolean withCount
    ) {
        currentUserContext.requireAdmin();
        try {
            return ApiResponse.success(service.listAll(protocol, withCount));
        } catch (Exception ex) {
            log.warn("Failed to list active proxies, returning empty list", ex);
            return ApiResponse.success(List.of());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminProxyResponse> getById(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getProxy(id));
    }

    @PostMapping
    public ApiResponse<AdminProxyResponse> create(@Valid @RequestBody CreateProxyRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.createProxy(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminProxyResponse> update(@PathVariable long id, @RequestBody UpdateProxyRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateProxy(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.deleteProxy(id));
    }

    @PostMapping("/{id}/test")
    public ApiResponse<TestProxyResponse> test(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.testProxy(id));
    }

    @PostMapping("/{id}/quality-check")
    public ApiResponse<ProxyQualityCheckResultResponse> qualityCheck(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.checkProxyQuality(id));
    }

    @GetMapping("/{id}/stats")
    public ApiResponse<ProxyStatsResponse> getStats(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getStats(id));
    }

    @GetMapping("/{id}/accounts")
    public ApiResponse<List<ProxyAccountSummaryResponse>> getProxyAccounts(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getProxyAccounts(id));
    }

    @PostMapping("/batch")
    public ApiResponse<BatchCreateProxiesResponse> batchCreate(@Valid @RequestBody BatchCreateProxiesRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.batchCreate(request));
    }

    @PostMapping("/batch-delete")
    public ApiResponse<BatchDeleteProxiesResponse> batchDelete(@Valid @RequestBody BatchDeleteProxiesRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.batchDelete(request.ids()));
    }

    @GetMapping("/data")
    public ApiResponse<AdminDataPayload> exportData(
            @RequestParam(required = false) List<Long> ids,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.exportData(ids, protocol, status, search, sortBy, sortOrder));
    }

    @PostMapping("/data")
    public ApiResponse<AdminDataImportResult> importData(@Valid @RequestBody ProxyDataImportRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.importData(request.data()));
    }
}
