package org.apiprivaterouter.javabackend.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.apiprivaterouter.javabackend.auth.model.OAuthAdoptionDecisionRequest;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthBindLoginRequest;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthCreateAccountRequest;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthExchangeRequest;
import org.apiprivaterouter.javabackend.auth.model.PendingOAuthSendVerifyCodeRequest;
import org.apiprivaterouter.javabackend.auth.service.PendingOAuthService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/auth/oauth")
public class PendingOAuthController {

    private final PendingOAuthService pendingOAuthService;
    private final ObjectMapper objectMapper;

    public PendingOAuthController(PendingOAuthService pendingOAuthService, ObjectMapper objectMapper) {
        this.pendingOAuthService = pendingOAuthService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/pending/exchange")
    public void exchange(
            @RequestBody(required = false) PendingOAuthExchangeRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        writeResult(servletResponse, pendingOAuthService.exchange(servletRequest, request));
    }

    @PostMapping("/pending/send-verify-code")
    public void sendVerifyCode(
            @Valid @RequestBody PendingOAuthSendVerifyCodeRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        writeResult(servletResponse, pendingOAuthService.sendVerifyCode(servletRequest, request));
    }

    @PostMapping("/pending/create-account")
    public void createAccount(
            @Valid @RequestBody PendingOAuthCreateAccountRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        writeResult(servletResponse, pendingOAuthService.createAccount(servletRequest, request, null));
    }

    @PostMapping("/pending/bind-login")
    public void bindLogin(
            @Valid @RequestBody PendingOAuthBindLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        writeResult(servletResponse, pendingOAuthService.bindLogin(servletRequest, request, null));
    }

    @PostMapping("/oidc/create-account")
    public void createOidcAccount(
            @Valid @RequestBody PendingOAuthCreateAccountRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        writeResult(servletResponse, pendingOAuthService.createAccount(servletRequest, request, "oidc"));
    }

    @PostMapping("/oidc/bind-login")
    public void bindOidcLogin(
            @Valid @RequestBody PendingOAuthBindLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        writeResult(servletResponse, pendingOAuthService.bindLogin(servletRequest, request, "oidc"));
    }

    @PostMapping("/wechat/create-account")
    public void createWeChatAccount(
            @Valid @RequestBody PendingOAuthCreateAccountRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        writeResult(servletResponse, pendingOAuthService.createAccount(servletRequest, request, "wechat"));
    }

    @PostMapping("/wechat/bind-login")
    public void bindWeChatLogin(
            @Valid @RequestBody PendingOAuthBindLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        writeResult(servletResponse, pendingOAuthService.bindLogin(servletRequest, request, "wechat"));
    }

    @PostMapping({"/community/create-account", "/linuxdo/create-account"})
    public void createLinuxDoAccount(
            @Valid @RequestBody PendingOAuthCreateAccountRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        writeResult(servletResponse, pendingOAuthService.createAccount(servletRequest, request, "linuxdo"));
    }

    @PostMapping({"/community/bind-login", "/linuxdo/bind-login"})
    public void bindLinuxDoLogin(
            @Valid @RequestBody PendingOAuthBindLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        writeResult(servletResponse, pendingOAuthService.bindLogin(servletRequest, request, "linuxdo"));
    }

    @PostMapping("/github/complete-registration")
    public void completeGitHubRegistration(
            @RequestBody java.util.Map<String, Object> request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        completeEmailOAuthRegistration("github", request, servletRequest, servletResponse);
    }

    @PostMapping("/google/complete-registration")
    public void completeGoogleRegistration(
            @RequestBody java.util.Map<String, Object> request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        completeEmailOAuthRegistration("google", request, servletRequest, servletResponse);
    }

    @PostMapping({"/community/complete-registration", "/linuxdo/complete-registration"})
    public void completeLinuxDoRegistration(
            @RequestBody java.util.Map<String, Object> request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        String invitationCode = valueAsString(request, "invitation_code");
        if (invitationCode.isBlank()) {
            throw new IllegalArgumentException("invitation_code is required");
        }
        writeResult(servletResponse, pendingOAuthService.completeSyntheticOAuthRegistration(
                servletRequest,
                "linuxdo",
                invitationCode,
                valueAsString(request, "aff_code"),
                adoptionDecision(request)
        ));
    }

    @PostMapping("/oidc/complete-registration")
    public void completeOidcRegistration(
            @RequestBody java.util.Map<String, Object> request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        String invitationCode = valueAsString(request, "invitation_code");
        if (invitationCode.isBlank()) {
            throw new IllegalArgumentException("invitation_code is required");
        }
        writeResult(servletResponse, pendingOAuthService.completeSyntheticOAuthRegistration(
                servletRequest,
                "oidc",
                invitationCode,
                valueAsString(request, "aff_code"),
                adoptionDecision(request)
        ));
    }

    @PostMapping("/wechat/complete-registration")
    public void completeWeChatRegistration(
            @RequestBody java.util.Map<String, Object> request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        String invitationCode = valueAsString(request, "invitation_code");
        if (invitationCode.isBlank()) {
            throw new IllegalArgumentException("invitation_code is required");
        }
        writeResult(servletResponse, pendingOAuthService.completeSyntheticOAuthRegistration(
                servletRequest,
                "wechat",
                invitationCode,
                valueAsString(request, "aff_code"),
                adoptionDecision(request)
        ));
    }

    private void completeEmailOAuthRegistration(
            String provider,
            java.util.Map<String, Object> request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) throws IOException {
        String password = valueAsString(request, "password");
        if (password.isBlank() || password.length() < 6) {
            throw new IllegalArgumentException("password size must be between 6 and 2147483647");
        }
        writeResult(servletResponse, pendingOAuthService.completeEmailOAuthRegistration(
                servletRequest,
                provider,
                password,
                valueAsString(request, "invitation_code"),
                valueAsString(request, "aff_code")
        ));
    }

    private String valueAsString(java.util.Map<String, Object> request, String key) {
        if (request == null || key == null) {
            return "";
        }
        Object value = request.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Boolean valueAsBoolean(java.util.Map<String, Object> request, String key) {
        if (request == null || key == null || !request.containsKey(key)) {
            return null;
        }
        Object value = request.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? null : Boolean.parseBoolean(text);
    }

    private OAuthAdoptionDecisionRequest adoptionDecision(java.util.Map<String, Object> request) {
        return new OAuthAdoptionDecisionRequest(
                valueAsBoolean(request, "adopt_display_name"),
                valueAsBoolean(request, "adopt_avatar")
        );
    }

    private void writeResult(HttpServletResponse response, PendingOAuthService.CookieResult<?> result) throws IOException {
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

}
