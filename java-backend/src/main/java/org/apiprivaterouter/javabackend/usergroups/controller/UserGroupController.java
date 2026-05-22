package org.apiprivaterouter.javabackend.usergroups.controller;

import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.usergroups.model.UserAvailableGroupResponse;
import org.apiprivaterouter.javabackend.usergroups.service.UserGroupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/groups")
public class UserGroupController {

    private final UserGroupService service;
    private final CurrentUserContext currentUserContext;

    public UserGroupController(UserGroupService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/available")
    public ApiResponse<List<UserAvailableGroupResponse>> getAvailable() {
        return ApiResponse.success(service.getAvailableGroups(currentUserContext.requireUser()));
    }

    @GetMapping("/rates")
    public ApiResponse<Map<Long, Double>> getUserGroupRates() {
        return ApiResponse.success(service.getUserGroupRates(currentUserContext.requireUser()));
    }
}
