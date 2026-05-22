package org.apiprivaterouter.javabackend.availablechannels.controller;

import org.apiprivaterouter.javabackend.availablechannels.model.UserAvailableChannelResponse;
import org.apiprivaterouter.javabackend.availablechannels.service.AvailableChannelsService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/channels")
public class AvailableChannelsController {

    private final AvailableChannelsService service;
    private final CurrentUserContext currentUserContext;

    public AvailableChannelsController(
            AvailableChannelsService service,
            CurrentUserContext currentUserContext
    ) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/available")
    public ApiResponse<List<UserAvailableChannelResponse>> getAvailable() {
        return ApiResponse.success(service.getAvailableChannels(currentUserContext.requireUser()));
    }
}
