package org.apiprivaterouter.javabackend.admin.channelmonitor.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.ChannelMonitorHistoryResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.ChannelMonitorResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.CreateChannelMonitorRequest;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.ListChannelMonitorsResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.RunNowResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.UpdateChannelMonitorRequest;
import org.apiprivaterouter.javabackend.admin.channelmonitor.service.ChannelMonitorService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
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

@RestController
@RequestMapping("/api/v1/admin/channel-monitors")
public class ChannelMonitorController {

    private final ChannelMonitorService service;
    private final CurrentUserContext currentUserContext;

    public ChannelMonitorController(ChannelMonitorService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<ListChannelMonitorsResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String search
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.list(page, pageSize, provider, enabled, search));
    }

    @GetMapping("/{id}")
    public ApiResponse<ChannelMonitorResponse> get(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<ChannelMonitorResponse> create(@Valid @RequestBody CreateChannelMonitorRequest request) {
        CurrentUser admin = currentUserContext.requireAdmin();
        return ApiResponse.success(service.create(admin.userId(), request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ChannelMonitorResponse> update(@PathVariable long id, @RequestBody UpdateChannelMonitorRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        currentUserContext.requireAdmin();
        service.delete(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/run")
    public ApiResponse<RunNowResponse> run(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.runNow(id));
    }

    @GetMapping("/{id}/history")
    public ApiResponse<ChannelMonitorHistoryResponse> history(
            @PathVariable long id,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) Integer limit
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.history(id, model, limit));
    }
}
