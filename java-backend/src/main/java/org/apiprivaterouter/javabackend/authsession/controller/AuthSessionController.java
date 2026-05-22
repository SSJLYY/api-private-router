package org.apiprivaterouter.javabackend.authsession.controller;

import org.apiprivaterouter.javabackend.authsession.service.AuthSessionService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthSessionController {

    private final CurrentUserContext currentUserContext;
    private final AuthSessionService authSessionService;

    public AuthSessionController(CurrentUserContext currentUserContext, AuthSessionService authSessionService) {
        this.currentUserContext = currentUserContext;
        this.authSessionService = authSessionService;
    }

    @PostMapping("/revoke-all-sessions")
    public ApiResponse<Map<String, String>> revokeAllSessions() {
        long userId = currentUserContext.requireUser().userId();
        return ApiResponse.success(authSessionService.revokeAllSessions(userId));
    }
}
