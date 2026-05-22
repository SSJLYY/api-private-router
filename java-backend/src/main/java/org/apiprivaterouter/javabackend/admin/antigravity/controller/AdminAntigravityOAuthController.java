package org.apiprivaterouter.javabackend.admin.antigravity.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.antigravity.model.AntigravityAuthUrlRequest;
import org.apiprivaterouter.javabackend.admin.antigravity.model.AntigravityAuthUrlResponse;
import org.apiprivaterouter.javabackend.admin.antigravity.model.AntigravityExchangeCodeRequest;
import org.apiprivaterouter.javabackend.admin.antigravity.model.AntigravityOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.antigravity.model.AntigravityRefreshTokenRequest;
import org.apiprivaterouter.javabackend.admin.antigravity.service.AntigravityOAuthService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/antigravity/oauth")
public class AdminAntigravityOAuthController {

    private final AntigravityOAuthService antigravityOAuthService;
    private final CurrentUserContext currentUserContext;

    public AdminAntigravityOAuthController(
            AntigravityOAuthService antigravityOAuthService,
            CurrentUserContext currentUserContext
    ) {
        this.antigravityOAuthService = antigravityOAuthService;
        this.currentUserContext = currentUserContext;
    }

    @PostMapping("/auth-url")
    public ApiResponse<AntigravityAuthUrlResponse> generateAuthUrl(
            @RequestBody(required = false) AntigravityAuthUrlRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(antigravityOAuthService.generateAuthUrl(
                request == null ? null : request.proxy_id()
        ));
    }

    @PostMapping("/exchange-code")
    public ApiResponse<AntigravityOAuthTokenResponse> exchangeCode(
            @Valid @RequestBody AntigravityExchangeCodeRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(antigravityOAuthService.exchangeCode(request));
    }

    @PostMapping("/refresh-token")
    public ApiResponse<AntigravityOAuthTokenResponse> refreshToken(
            @RequestBody AntigravityRefreshTokenRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(antigravityOAuthService.refreshToken(
                request == null ? null : request.refresh_token(),
                request == null ? null : request.proxy_id()
        ));
    }
}
