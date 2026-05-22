package org.apiprivaterouter.javabackend.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.apiprivaterouter.javabackend.auth.service.WeChatOAuthService;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/auth/oauth/wechat")
public class WeChatOAuthController {

    private final WeChatOAuthService service;

    public WeChatOAuthController(WeChatOAuthService service) {
        this.service = service;
    }

    @GetMapping("/start")
    public void start(
            @RequestParam(value = "redirect", required = false) String redirect,
            @RequestParam(value = "intent", required = false) String intent,
            @RequestParam(value = "mode", required = false) String mode,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        writeStartResult(service.buildStartResult(request, redirect, intent, mode), response);
    }

    @GetMapping("/bind/start")
    public void bindStart(
            @RequestParam(value = "redirect", required = false) String redirect,
            @RequestParam(value = "intent", required = false) String intent,
            @RequestParam(value = "mode", required = false) String mode,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        String resolvedIntent = intent == null || intent.trim().isEmpty() ? "bind_current_user" : intent;
        writeStartResult(service.buildStartResult(request, redirect, resolvedIntent, mode), response);
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
        WeChatOAuthService.CallbackResult result = service.handleCallback(request, code, state, error, errorDescription);
        response.addHeader(HttpHeaders.SET_COOKIE, result.stateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.redirectCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.intentCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.modeCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.bindUserCookie().toString());
        if (result.pendingSessionCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.pendingSessionCookie().toString());
        }
        if (result.browserCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.browserCookie().toString());
        }
        response.sendRedirect(result.redirectUrl());
    }

    private void writeStartResult(WeChatOAuthService.StartResult result, HttpServletResponse response) throws IOException {
        response.addHeader(HttpHeaders.SET_COOKIE, result.stateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.redirectCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.intentCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.modeCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.bindUserCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.browserCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.pendingSessionCookie().toString());
        response.sendRedirect(result.authorizeUrl());
    }
}
