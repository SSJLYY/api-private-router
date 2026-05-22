package org.apiprivaterouter.javabackend.channelmonitor.controller;

import org.apiprivaterouter.javabackend.channelmonitor.model.UserChannelMonitorDetailResponse;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserChannelMonitorListResponse;
import org.apiprivaterouter.javabackend.channelmonitor.service.ChannelMonitorService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller("userChannelMonitorController")
@RequestMapping("/api/v1/channel-monitors")
public class ChannelMonitorController {

    private final ChannelMonitorService service;
    private final CurrentUserContext currentUserContext;

    public ChannelMonitorController(ChannelMonitorService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    @ResponseBody
    public ApiResponse<UserChannelMonitorListResponse> list() {
        currentUserContext.requireUser();
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}/status")
    @ResponseBody
    public ApiResponse<UserChannelMonitorDetailResponse> status(@PathVariable long id) {
        currentUserContext.requireUser();
        return ApiResponse.success(service.status(id));
    }
}
