package org.apiprivaterouter.javabackend.admin.gemini.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiAuthUrlRequest;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiAuthUrlResponse;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiExchangeCodeRequest;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiOAuthCapabilitiesResponse;
import org.apiprivaterouter.javabackend.admin.gemini.model.GeminiOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.gemini.service.GeminiOAuthGatewayService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/gemini/oauth")
public class AdminGeminiOAuthController {

    private final GeminiOAuthGatewayService geminiOAuthGatewayService;
    private final CurrentUserContext currentUserContext;

    public AdminGeminiOAuthController(
            GeminiOAuthGatewayService geminiOAuthGatewayService,
            CurrentUserContext currentUserContext
    ) {
        this.geminiOAuthGatewayService = geminiOAuthGatewayService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/capabilities")
    public ApiResponse<GeminiOAuthCapabilitiesResponse> getCapabilities() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(geminiOAuthGatewayService.getCapabilities());
    }

    @PostMapping("/auth-url")
    public ApiResponse<GeminiAuthUrlResponse> generateAuthUrl(
            @RequestBody(required = false) GeminiAuthUrlRequest request,
            @RequestHeader(name = "Origin", required = false) String origin,
            @RequestHeader(name = "X-Forwarded-Proto", required = false) String forwardedProto,
            @RequestHeader(name = "X-Forwarded-Host", required = false) String forwardedHost,
            jakarta.servlet.http.HttpServletRequest httpRequest
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(geminiOAuthGatewayService.generateAuthUrl(
                request,
                origin,
                forwardedProto,
                forwardedHost,
                httpRequest
        ));
    }

    @PostMapping("/exchange-code")
    public ApiResponse<GeminiOAuthTokenResponse> exchangeCode(@Valid @RequestBody GeminiExchangeCodeRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(geminiOAuthGatewayService.exchangeCode(request));
    }
}
