package org.apiprivaterouter.javabackend.admin.channelmonitortemplate.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.ApplyChannelMonitorTemplateRequest;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.ApplyTemplateResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.AssociatedMonitorsResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.ChannelMonitorTemplateResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.CreateChannelMonitorTemplateRequest;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.ListChannelMonitorTemplatesResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.UpdateChannelMonitorTemplateRequest;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.service.ChannelMonitorTemplateService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
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
@RequestMapping("/api/v1/admin/channel-monitor-templates")
public class ChannelMonitorTemplateController {

    private final ChannelMonitorTemplateService service;
    private final CurrentUserContext currentUserContext;

    public ChannelMonitorTemplateController(ChannelMonitorTemplateService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<ListChannelMonitorTemplatesResponse> list(@RequestParam(required = false) String provider) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.list(provider));
    }

    @GetMapping("/{id}")
    public ApiResponse<ChannelMonitorTemplateResponse> get(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<ChannelMonitorTemplateResponse> create(@Valid @RequestBody CreateChannelMonitorTemplateRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ChannelMonitorTemplateResponse> update(
            @PathVariable long id,
            @RequestBody UpdateChannelMonitorTemplateRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        currentUserContext.requireAdmin();
        service.delete(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/apply")
    public ApiResponse<ApplyTemplateResponse> apply(
            @PathVariable long id,
            @Valid @RequestBody ApplyChannelMonitorTemplateRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.apply(id, request));
    }

    @GetMapping("/{id}/monitors")
    public ApiResponse<AssociatedMonitorsResponse> listAssociatedMonitors(@PathVariable long id) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listAssociatedMonitors(id));
    }
}
