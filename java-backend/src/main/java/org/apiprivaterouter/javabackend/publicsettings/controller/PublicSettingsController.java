package org.apiprivaterouter.javabackend.publicsettings.controller;

import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.publicsettings.model.PublicSettingsResponse;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class PublicSettingsController {

    private final PublicSettingsService service;

    public PublicSettingsController(PublicSettingsService service) {
        this.service = service;
    }

    @GetMapping("/public")
    public ApiResponse<PublicSettingsResponse> getPublicSettings() {
        return ApiResponse.success(service.getPublicSettings());
    }
}
