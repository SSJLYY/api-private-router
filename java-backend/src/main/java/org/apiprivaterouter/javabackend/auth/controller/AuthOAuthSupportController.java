package org.apiprivaterouter.javabackend.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.apiprivaterouter.javabackend.auth.service.OAuthBindCookieService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.UnauthorizedException;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthOAuthSupportController {

    private final OAuthBindCookieService oauthBindCookieService;
    private final CurrentUserContext currentUserContext;

    public AuthOAuthSupportController(
            OAuthBindCookieService oauthBindCookieService,
            CurrentUserContext currentUserContext
    ) {
        this.oauthBindCookieService = oauthBindCookieService;
        this.currentUserContext = currentUserContext;
    }

    @PostMapping("/oauth/bind-token")
    public void prepareBindToken(HttpServletRequest request, HttpServletResponse response) throws IOException {
        CurrentUser currentUser = currentUserContext.requireUser();
        if (currentUser == null || currentUser.userId() <= 0) {
            throw new UnauthorizedException("authentication required");
        }
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedException("authentication required");
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            throw new UnauthorizedException("authentication required");
        }
        response.addHeader(HttpHeaders.SET_COOKIE, oauthBindCookieService.buildAccessTokenCookie(
                token,
                oauthBindCookieService.isSecure(request)
        ).toString());
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        response.flushBuffer();
    }
}
