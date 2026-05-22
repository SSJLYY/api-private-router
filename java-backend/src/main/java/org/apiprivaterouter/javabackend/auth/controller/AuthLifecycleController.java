package org.apiprivaterouter.javabackend.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.auth.model.AuthForgotPasswordRequest;
import org.apiprivaterouter.javabackend.auth.model.AuthLogin2faRequest;
import org.apiprivaterouter.javabackend.auth.model.AuthLoginRequest;
import org.apiprivaterouter.javabackend.auth.model.AuthLogoutRequest;
import org.apiprivaterouter.javabackend.auth.model.AuthRefreshTokenRequest;
import org.apiprivaterouter.javabackend.auth.model.AuthRegisterRequest;
import org.apiprivaterouter.javabackend.auth.model.AuthResetPasswordRequest;
import org.apiprivaterouter.javabackend.auth.model.AuthSendVerifyCodeRequest;
import org.apiprivaterouter.javabackend.auth.model.AuthTokenResponse;
import org.apiprivaterouter.javabackend.auth.model.AuthValidateInvitationCodeRequest;
import org.apiprivaterouter.javabackend.auth.model.AuthValidatePromoCodeRequest;
import org.apiprivaterouter.javabackend.auth.service.AuthLifecycleService;
import org.apiprivaterouter.javabackend.auth.service.PendingOAuthTotpSessionStore;
import org.apiprivaterouter.javabackend.auth.service.PendingOAuthService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthLifecycleController {

    private final AuthLifecycleService authLifecycleService;
    private final ObjectMapper objectMapper;
    private final PendingOAuthService pendingOAuthService;
    private final PendingOAuthTotpSessionStore pendingOAuthTotpSessionStore;

    public AuthLifecycleController(
            AuthLifecycleService authLifecycleService,
            ObjectMapper objectMapper,
            PendingOAuthService pendingOAuthService,
            PendingOAuthTotpSessionStore pendingOAuthTotpSessionStore
    ) {
        this.authLifecycleService = authLifecycleService;
        this.objectMapper = objectMapper;
        this.pendingOAuthService = pendingOAuthService;
        this.pendingOAuthTotpSessionStore = pendingOAuthTotpSessionStore;
    }

    @PostMapping("/login")
    public ApiResponse<Object> login(
            @Valid @RequestBody AuthLoginRequest request,
            HttpServletRequest servletRequest
    ) {
        AuthLifecycleService.LoginOutcome outcome = authLifecycleService.login(
                request.email(),
                request.password(),
                request.turnstile_token(),
                servletRequest.getRemoteAddr()
        );
        return ApiResponse.success(outcome.requiresTotp() ? outcome.totpResponse() : outcome.authResponse());
    }

    @PostMapping("/send-verify-code")
    public ApiResponse<Object> sendVerifyCode(
            @Valid @RequestBody AuthSendVerifyCodeRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.success(authLifecycleService.sendVerifyCode(
                request.email(),
                request.turnstile_token(),
                servletRequest.getRemoteAddr()
        ));
    }

    @PostMapping("/register")
    public ApiResponse<Object> register(
            @Valid @RequestBody AuthRegisterRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.success(authLifecycleService.register(
                request.email(),
                request.password(),
                request.verify_code(),
                request.turnstile_token(),
                servletRequest.getRemoteAddr(),
                request.promo_code(),
                request.invitation_code(),
                request.aff_code()
        ));
    }

    @PostMapping("/forgot-password")
    public ApiResponse<Object> forgotPassword(
            @Valid @RequestBody AuthForgotPasswordRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.success(authLifecycleService.forgotPassword(
                request.email(),
                request.turnstile_token(),
                servletRequest.getRemoteAddr()
        ));
    }

    @PostMapping("/reset-password")
    public ApiResponse<Object> resetPassword(
            @Valid @RequestBody AuthResetPasswordRequest request
    ) {
        return ApiResponse.success(authLifecycleService.resetPassword(
                request.email(),
                request.token(),
                request.new_password()
        ));
    }

    @PostMapping("/validate-promo-code")
    public ApiResponse<Object> validatePromoCode(
            @Valid @RequestBody AuthValidatePromoCodeRequest request
    ) {
        return ApiResponse.success(authLifecycleService.validatePromoCode(request.code()));
    }

    @PostMapping("/validate-invitation-code")
    public ApiResponse<Object> validateInvitationCode(
            @Valid @RequestBody AuthValidateInvitationCodeRequest request
    ) {
        return ApiResponse.success(authLifecycleService.validateInvitationCode(request.code()));
    }

    @PostMapping("/login/2fa")
    public void login2fa(
            @Valid @RequestBody AuthLogin2faRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        PendingOAuthTotpSessionStore.PendingSessionBinding pendingBinding =
                pendingOAuthTotpSessionStore.get(request.temp_token());
        if (pendingBinding != null) {
            AuthTokenResponse response = authLifecycleService.completeLogin2fa(request.temp_token(), request.totp_code());
            pendingOAuthService.finalizePendingTotpLogin(
                    pendingBinding.pendingSessionId(),
                    pendingBinding.browserSessionKey(),
                    pendingBinding.adoptionDecision(),
                    pendingBinding.userId()
            );
            pendingOAuthTotpSessionStore.delete(request.temp_token());
            writeSuccess(servletResponse, response);
            return;
        }
        writeSuccess(servletResponse, authLifecycleService.completeLogin2fa(request.temp_token(), request.totp_code()));
    }

    @PostMapping("/refresh")
    public void refresh(
            @Valid @RequestBody AuthRefreshTokenRequest request,
            HttpServletResponse servletResponse
    ) throws IOException {
        writeSuccess(servletResponse, authLifecycleService.refresh(request.refresh_token()));
    }

    @PostMapping("/logout")
    public void logout(
            @RequestBody(required = false) AuthLogoutRequest request,
            HttpServletResponse servletResponse
    ) throws IOException {
        String refreshToken = request == null ? null : request.refresh_token();
        authLifecycleService.logout(refreshToken);
        writeSuccess(servletResponse, Map.of("message", "Logged out successfully"));
    }

    private void writeSuccess(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.success(data));
        response.flushBuffer();
    }
}
