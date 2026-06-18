package org.apiprivaterouter.javabackend.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.apiprivaterouter.javabackend.auth.service.DingTalkOAuthService;
import org.apiprivaterouter.javabackend.auth.service.PendingOAuthService;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthCreateAccountRequest;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthBindLoginRequest;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/oauth/dingtalk")
public class DingTalkOAuthController {

    private final DingTalkOAuthService dingTalkOAuthService;
    private final PendingOAuthService pendingOAuthService;
    private final ObjectMapper objectMapper;

    public DingTalkOAuthController(
            DingTalkOAuthService dingTalkOAuthService,
            PendingOAuthService pendingOAuthService,
            ObjectMapper objectMapper
    ) {
        this.dingTalkOAuthService = dingTalkOAuthService;
        this.pendingOAuthService = pendingOAuthService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/start")
    public void start(
            @RequestParam(value = "redirect", required = false) String redirect,
            @RequestParam(value = "aff_code", required = false) String affiliateCode,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        writeStartResult(response, dingTalkOAuthService.buildStartResult(request, redirect, affiliateCode, false));
    }

    @GetMapping("/bind/start")
    public void bindStart(
            @RequestParam(value = "redirect", required = false) String redirect,
            @RequestParam(value = "aff_code", required = false) String affiliateCode,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        writeStartResult(response, dingTalkOAuthService.buildStartResult(request, redirect, affiliateCode, true));
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
        DingTalkOAuthService.CallbackResult result = dingTalkOAuthService.handleCallback(request, code, state, error, errorDescription);
        response.addHeader(HttpHeaders.SET_COOKIE, result.stateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.redirectCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.intentCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.bindUserCookie().toString());
        if (result.pendingSessionCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.pendingSessionCookie().toString());
        }
        if (result.browserCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.browserCookie().toString());
        }
        response.sendRedirect(result.redirectUrl());
    }

    @PostMapping("/complete-registration")
    public void completeRegistration(
            @RequestBody Map<String, Object> request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        String invitationCode = valueAsString(request, "invitation_code");
        if (invitationCode.isBlank()) {
            throw new IllegalArgumentException("invitation_code is required");
        }
        Object result = dingTalkOAuthService.completeRegistration(
                servletRequest,
                invitationCode,
                valueAsString(request, "aff_code")
        );
        writeJsonResult(servletResponse, result);
    }

    @PostMapping("/create-account")
    public void createAccount(
            @RequestBody PendingOAuthCreateAccountRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        writePendingResult(servletResponse, pendingOAuthService.createAccount(servletRequest, request, "dingtalk"));
    }

    @PostMapping("/bind-login")
    public void bindLogin(
            @RequestBody PendingOAuthBindLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        writePendingResult(servletResponse, pendingOAuthService.bindLogin(servletRequest, request, "dingtalk"));
    }

    private void writeStartResult(HttpServletResponse response, DingTalkOAuthService.StartResult result) throws IOException {
        response.addHeader(HttpHeaders.SET_COOKIE, result.stateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.redirectCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.intentCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.bindUserCookie().toString());
        if (result.browserCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.browserCookie().toString());
        }
        if (result.pendingSessionCookie() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, result.pendingSessionCookie().toString());
        }
        response.sendRedirect(result.authorizeUrl());
    }

    private void writePendingResult(HttpServletResponse response, PendingOAuthService.CookieResult<?> result) throws IOException {
        if (result.cookies() != null) {
            for (var cookie : result.cookies()) {
                if (cookie != null) {
                    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
                }
            }
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.success(result.body()));
        response.flushBuffer();
    }

    private void writeJsonResult(HttpServletResponse response, Object result) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.success(result));
        response.flushBuffer();
    }

    private String valueAsString(Map<String, Object> request, String key) {
        if (request == null || key == null) {
            return "";
        }
        Object value = request.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
