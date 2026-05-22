package org.apiprivaterouter.javabackend.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.apiprivaterouter.javabackend.auth.service.EmailOAuthService;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/auth/oauth")
public class EmailOAuthController {

    private final EmailOAuthService service;

    public EmailOAuthController(EmailOAuthService service) {
        this.service = service;
    }

    @GetMapping("/github/start")
    public void startGitHub(
            @RequestParam(value = "redirect", required = false) String redirect,
            @RequestParam(value = "aff_code", required = false) String affiliateCode,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        start("github", redirect, affiliateCode, request, response);
    }

    @GetMapping("/google/start")
    public void startGoogle(
            @RequestParam(value = "redirect", required = false) String redirect,
            @RequestParam(value = "aff_code", required = false) String affiliateCode,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        start("google", redirect, affiliateCode, request, response);
    }

    @GetMapping("/github/callback")
    public void callbackGitHub(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        callback("github", code, state, error, errorDescription, request, response);
    }

    @GetMapping("/google/callback")
    public void callbackGoogle(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_description", required = false) String errorDescription,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        callback("google", code, state, error, errorDescription, request, response);
    }

    private void start(
            String provider,
            String redirect,
            String affiliateCode,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        EmailOAuthService.StartResult result = service.buildStartResult(request, provider, redirect, affiliateCode);
        response.addHeader(HttpHeaders.SET_COOKIE, result.stateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.redirectCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.providerCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.affiliateCookie().toString());
        response.sendRedirect(result.authorizeUrl());
    }

    private void callback(
            String provider,
            String code,
            String state,
            String error,
            String errorDescription,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        EmailOAuthService.CallbackResult result = service.handleCallback(
                request,
                provider,
                code,
                state,
                error,
                errorDescription
        );
        response.addHeader(HttpHeaders.SET_COOKIE, result.stateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.redirectCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.providerCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.affiliateCookie().toString());
        if (result.pendingSessionCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.pendingSessionCookie().toString());
        }
        if (result.pendingBrowserCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.pendingBrowserCookie().toString());
        }
        response.sendRedirect(result.redirectUrl());
    }
}
