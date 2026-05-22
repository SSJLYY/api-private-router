package org.apiprivaterouter.javabackend.admin.group.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.group.model.AdminGroupResponse;
import org.apiprivaterouter.javabackend.admin.group.model.BatchSetGroupRateMultipliersRequest;
import org.apiprivaterouter.javabackend.admin.group.model.BatchSetGroupRpmOverridesRequest;
import org.apiprivaterouter.javabackend.admin.group.model.CreateAdminGroupRequest;
import org.apiprivaterouter.javabackend.admin.group.model.GroupCapacitySummaryResponse;
import org.apiprivaterouter.javabackend.admin.group.model.GroupRateMultiplierEntryResponse;
import org.apiprivaterouter.javabackend.admin.group.model.GroupStatsResponse;
import org.apiprivaterouter.javabackend.admin.group.model.GroupUsageSummaryResponse;
import org.apiprivaterouter.javabackend.admin.group.model.UpdateAdminGroupRequest;
import org.apiprivaterouter.javabackend.admin.group.model.UpdateGroupSortOrderRequest;
import org.apiprivaterouter.javabackend.admin.group.service.AdminGroupService;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/groups")
public class AdminGroupController {

    private final AdminGroupService service;
    private final CurrentUserContext currentUserContext;

    public AdminGroupController(AdminGroupService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminGroupResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(name = "is_exclusive", required = false) Boolean isExclusive,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listGroups(page, pageSize, platform, status, search, isExclusive, sortBy, sortOrder));
    }

    @GetMapping("/all")
    public ApiResponse<List<AdminGroupResponse>> getAll(@RequestParam(required = false) String platform) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listAllGroups(platform));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminGroupResponse> getById(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getGroup(id));
    }

    @PostMapping
    public ApiResponse<AdminGroupResponse> create(@Valid @RequestBody CreateAdminGroupRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.createGroup(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminGroupResponse> update(@PathVariable long id, @Valid @RequestBody UpdateAdminGroupRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateGroup(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.deleteGroup(id));
    }

    @GetMapping("/{id}/stats")
    public ApiResponse<GroupStatsResponse> getStats(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getGroupStats(id));
    }

    @GetMapping("/{id}/api-keys")
    public ApiResponse<PageResponse<Map<String, Object>>> getApiKeys(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getGroupApiKeys(id, page, pageSize));
    }

    @GetMapping("/{id}/rate-multipliers")
    public ApiResponse<List<GroupRateMultiplierEntryResponse>> getRateMultipliers(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getGroupRateMultipliers(id));
    }

    @DeleteMapping("/{id}/rate-multipliers")
    public ApiResponse<Map<String, String>> clearRateMultipliers(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.clearGroupRateMultipliers(id));
    }

    @PutMapping("/{id}/rate-multipliers")
    public ApiResponse<Map<String, String>> batchSetRateMultipliers(
            @PathVariable long id,
            @Valid @RequestBody BatchSetGroupRateMultipliersRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.batchSetGroupRateMultipliers(id, request));
    }

    @DeleteMapping("/{id}/rpm-overrides")
    public ApiResponse<Map<String, String>> clearRpmOverrides(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.clearGroupRpmOverrides(id));
    }

    @PutMapping("/{id}/rpm-overrides")
    public ApiResponse<Map<String, String>> batchSetRpmOverrides(
            @PathVariable long id,
            @Valid @RequestBody BatchSetGroupRpmOverridesRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.batchSetGroupRpmOverrides(id, request));
    }

    @PutMapping("/sort-order")
    public ApiResponse<Map<String, String>> updateSortOrder(@Valid @RequestBody UpdateGroupSortOrderRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateSortOrder(request));
    }

    @GetMapping("/usage-summary")
    public ApiResponse<List<GroupUsageSummaryResponse>> getUsageSummary(@RequestParam(required = false) String timezone) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getUsageSummary(timezone));
    }

    @GetMapping("/capacity-summary")
    public ApiResponse<List<GroupCapacitySummaryResponse>> getCapacitySummary() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getCapacitySummary());
    }
}
