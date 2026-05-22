package org.apiprivaterouter.javabackend.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.auth.service.WeChatPaymentOAuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/auth/oauth/wechat/payment")
public class WeChatPaymentOAuthController {

    private final WeChatPaymentOAuthService service;

    public WeChatPaymentOAuthController(WeChatPaymentOAuthService service) {
        this.service = service;
    }

    @GetMapping("/start")
    public void start(
            @RequestParam("payment_type") String paymentType,
            @RequestParam(value = "redirect", required = false) String redirect,
            @RequestParam(value = "amount", required = false) String amount,
            @RequestParam(value = "order_type", required = false) String orderType,
            @RequestParam(value = "plan_id", required = false) Long planId,
            @RequestParam(value = "scope", required = false) String scope,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        WeChatPaymentOAuthService.StartResult result = service.buildStartResult(
                request,
                paymentType,
                redirect,
                amount,
                orderType,
                planId,
                scope
        );
        response.addHeader(HttpHeaders.SET_COOKIE, result.stateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.redirectCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.contextCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.scopeCookie().toString());
        response.sendRedirect(result.authorizeUrl());
    }

    @GetMapping("/callback")
    public void callback(
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        WeChatPaymentOAuthService.CallbackResult result = service.handleCallback(request, code, state);
        response.addHeader(HttpHeaders.SET_COOKIE, result.stateCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.redirectCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.contextCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, result.scopeCookie().toString());
        response.sendRedirect(result.redirectUrl());
    }
}
