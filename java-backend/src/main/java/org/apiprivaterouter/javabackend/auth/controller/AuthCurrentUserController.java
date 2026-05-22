package org.apiprivaterouter.javabackend.auth.controller;

import org.apiprivaterouter.javabackend.auth.model.CurrentUserResponse;
import org.apiprivaterouter.javabackend.auth.service.CurrentUserService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthCurrentUserController {

    private final CurrentUserService currentUserService;
    private final CurrentUserContext currentUserContext;

    public AuthCurrentUserController(
            CurrentUserService currentUserService,
            CurrentUserContext currentUserContext
    ) {
        this.currentUserService = currentUserService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> getCurrentUser() {
        CurrentUser currentUser = currentUserContext.requireUser();
        return ApiResponse.success(currentUserService.getCurrentUser(currentUser));
    }
}
