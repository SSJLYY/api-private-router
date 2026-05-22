package org.apiprivaterouter.javabackend.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.apiprivaterouter.javabackend.auth.service.LinuxDoOAuthService;

import java.io.IOException;

@RestController
@RequestMapping({"/api/v1/auth/oauth/community", "/api/v1/auth/oauth/linuxdo"})
public class CommunityOAuthController {

    private final LinuxDoOAuthService service;

    public CommunityOAuthController(LinuxDoOAuthService service) {
        this.service = service;
    }

    @GetMapping("/start")
    public void start(
            @RequestParam(value = "redirect", required = false) String redirect,
            @RequestParam(value = "aff_code", required = false) String affiliateCode,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        writeStartResult(response, service.buildStartResult(request, redirect, affiliateCode, false));
    }

    @GetMapping("/bind/start")
    public void bindStart(
            @RequestParam(value = "redirect", required = false) String redirect,
            @RequestParam(value = "aff_code", required = false) String affiliateCode,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        writeStartResult(response, service.buildStartResult(request, redirect, affiliateCode, true));
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
        LinuxDoOAuthService.CallbackResult result = service.handleCallback(request, code, state, error, errorDescription);
        response.addHeader(HttpHeaders.SET_COOKIE, result.stateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.redirectCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.intentCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.verifierCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.bindUserCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.affiliateCookie().toString());
        if (result.pendingSessionCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.pendingSessionCookie().toString());
        }
        if (result.browserCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.browserCookie().toString());
        }
        response.sendRedirect(result.redirectUrl());
    }

    private void writeStartResult(HttpServletResponse response, LinuxDoOAuthService.StartResult result) throws IOException {
        response.addHeader(HttpHeaders.SET_COOKIE, result.stateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.redirectCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.intentCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.verifierCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.bindUserCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.affiliateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.browserCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.pendingSessionCookie().toString());
        response.sendRedirect(result.authorizeUrl());
    }
}
