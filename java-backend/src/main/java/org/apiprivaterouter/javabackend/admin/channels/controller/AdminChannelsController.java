package org.apiprivaterouter.javabackend.admin.channels.controller;

import org.apiprivaterouter.javabackend.admin.channels.model.AdminChannelResponse;
import org.apiprivaterouter.javabackend.admin.channels.model.CreateAdminChannelRequest;
import org.apiprivaterouter.javabackend.admin.channels.model.ModelDefaultPricingResponse;
import org.apiprivaterouter.javabackend.admin.channels.model.UpdateAdminChannelRequest;
import org.apiprivaterouter.javabackend.admin.channels.service.AdminChannelsService;
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
@RequestMapping("/api/v1/admin/channels")
public class AdminChannelsController {

    private final AdminChannelsService service;
    private final CurrentUserContext currentUserContext;

    public AdminChannelsController(AdminChannelsService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminChannelResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(name = "sort_by", required = false) String sortBy,
            @RequestParam(name = "sort_order", required = false) String sortOrder
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listChannels(page, pageSize, status, search, sortBy, sortOrder));
    }

    @GetMapping("/{id}")
    public ApiResponse<AdminChannelResponse> getById(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getChannel(id));
    }

    @PostMapping
    public ApiResponse<AdminChannelResponse> create(@RequestBody CreateAdminChannelRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.createChannel(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AdminChannelResponse> update(@PathVariable long id, @RequestBody UpdateAdminChannelRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateChannel(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.deleteChannel(id));
    }

    @GetMapping("/model-pricing")
    public ApiResponse<ModelDefaultPricingResponse> getModelPricing(@RequestParam String model) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getModelDefaultPricing(model));
    }
}
