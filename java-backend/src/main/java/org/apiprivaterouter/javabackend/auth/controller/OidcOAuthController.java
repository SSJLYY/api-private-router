package org.apiprivaterouter.javabackend.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.apiprivaterouter.javabackend.auth.service.OidcOAuthService;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/auth/oauth/oidc")
public class OidcOAuthController {

    private final OidcOAuthService service;

    public OidcOAuthController(OidcOAuthService service) {
        this.service = service;
    }

    @GetMapping("/start")
    public void start(
            @RequestParam(value = "redirect", required = false) String redirect,
            @RequestParam(value = "intent", required = false) String intent,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        writeStartResult(service.buildStartResult(request, redirect, intent), response);
    }

    @GetMapping("/bind/start")
    public void bindStart(
            @RequestParam(value = "redirect", required = false) String redirect,
            @RequestParam(value = "intent", required = false) String intent,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        String resolvedIntent = intent == null || intent.trim().isEmpty() ? "bind_current_user" : intent;
        writeStartResult(service.buildStartResult(request, redirect, resolvedIntent), response);
    }

    @GetMapping("/callback")
    public void callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        OidcOAuthService.CallbackResult result = service.handleCallback(request, code, state, error, errorDescription);
        response.addHeader(HttpHeaders.SET_COOKIE, result.stateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.redirectCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.verifierCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.nonceCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.intentCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.bindUserCookie().toString());
        if (result.pendingSessionCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.pendingSessionCookie().toString());
        }
        if (result.pendingBrowserCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.pendingBrowserCookie().toString());
        }
        response.sendRedirect(result.redirectUrl());
    }

    private void writeStartResult(OidcOAuthService.StartResult result, HttpServletResponse response) throws IOException {
        response.addHeader(HttpHeaders.SET_COOKIE, result.stateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.redirectCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.intentCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.verifierCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.nonceCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.bindUserCookie().toString());
        if (result.pendingBrowserCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.pendingBrowserCookie().toString());
        }
        if (result.pendingSessionCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.pendingSessionCookie().toString());
        }
        response.sendRedirect(result.authorizeUrl());
    }
}
