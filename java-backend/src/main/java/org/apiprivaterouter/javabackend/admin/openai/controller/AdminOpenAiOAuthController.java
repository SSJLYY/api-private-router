package org.apiprivaterouter.javabackend.admin.openai.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.admin.account.model.CreateAccountRequest;
import org.apiprivaterouter.javabackend.admin.account.model.UpdateAccountRequest;
import org.apiprivaterouter.javabackend.admin.account.model.GenerateAuthUrlResponse;
import org.apiprivaterouter.javabackend.admin.account.service.AdminAccountService;
import org.apiprivaterouter.javabackend.admin.openai.model.OpenAiCreateAccountFromOAuthRequest;
import org.apiprivaterouter.javabackend.admin.openai.model.OpenAiExchangeCodeRequest;
import org.apiprivaterouter.javabackend.admin.openai.model.OpenAiGenerateAuthUrlRequest;
import org.apiprivaterouter.javabackend.admin.openai.model.OpenAiOAuthTokenResponse;
import org.apiprivaterouter.javabackend.admin.openai.model.OpenAiRefreshTokenRequest;
import org.apiprivaterouter.javabackend.admin.openai.service.OpenAiOAuthService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/openai")
public class AdminOpenAiOAuthController {

    private final OpenAiOAuthService openAiOAuthService;
    private final AdminAccountService adminAccountService;
    private final CurrentUserContext currentUserContext;

    public AdminOpenAiOAuthController(
            OpenAiOAuthService openAiOAuthService,
            AdminAccountService adminAccountService,
            CurrentUserContext currentUserContext
    ) {
        this.openAiOAuthService = openAiOAuthService;
        this.adminAccountService = adminAccountService;
        this.currentUserContext = currentUserContext;
    }

    @PostMapping("/generate-auth-url")
    public ApiResponse<GenerateAuthUrlResponse> generateAuthUrl(@RequestBody(required = false) OpenAiGenerateAuthUrlRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(openAiOAuthService.generateAuthUrl(
                request == null ? null : request.proxy_id(),
                request == null ? null : request.redirect_uri()
        ));
    }

    @PostMapping("/exchange-code")
    public ApiResponse<OpenAiOAuthTokenResponse> exchangeCode(@Valid @RequestBody OpenAiExchangeCodeRequest request) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(openAiOAuthService.exchangeCode(request));
    }

    @PostMapping("/refresh-token")
    public ApiResponse<OpenAiOAuthTokenResponse> refreshToken(@RequestBody OpenAiRefreshTokenRequest request) {
        currentUserContext.requireAdmin();
        String refreshToken = null;
        if (request != null) {
            refreshToken = request.refresh_token();
            if (refreshToken == null || refreshToken.isBlank()) {
                refreshToken = request.rt();
            }
        }
        return ApiResponse.success(openAiOAuthService.refreshToken(
                refreshToken,
                request == null ? null : request.client_id(),
                request == null ? null : request.proxy_id()
        ));
    }

    @PostMapping("/accounts/{id}/refresh")
    public ApiResponse<AdminAccountResponse> refreshAccountToken(@PathVariable long id) {
        currentUserContext.requireAdmin();
        AdminAccountResponse current = adminAccountService.getAccount(id);
        OpenAiOAuthTokenResponse tokenInfo = openAiOAuthService.refreshAccountToken(current);

        Map<String, Object> credentials = new LinkedHashMap<>(current.credentials() == null ? Map.of() : current.credentials());
        credentials.putAll(openAiOAuthService.buildAccountCredentials(tokenInfo));

        UpdateAccountRequest request = new UpdateAccountRequest();
        request.setCredentials(credentials);
        Map<String, Object> extra = openAiOAuthService.mergeExtra(current.extra(), tokenInfo);
        if (!extra.equals(current.extra())) {
            request.setExtra(extra);
        }
        return ApiResponse.success(adminAccountService.updateAccount(id, request));
    }

    @PostMapping("/create-from-oauth")
    public ApiResponse<AdminAccountResponse> createFromOAuth(@Valid @RequestBody OpenAiCreateAccountFromOAuthRequest request) {
        currentUserContext.requireAdmin();

        OpenAiOAuthTokenResponse tokenInfo = openAiOAuthService.exchangeCode(new OpenAiExchangeCodeRequest(
                request.session_id(),
                request.code(),
                request.state(),
                request.redirect_uri(),
                request.proxy_id()
        ));

        Map<String, Object> credentials = openAiOAuthService.buildAccountCredentials(tokenInfo);
        Map<String, Object> extra = openAiOAuthService.mergeExtra(null, tokenInfo);
        String name = request.name();
        if (name == null || name.isBlank()) {
            name = tokenInfo.email();
        }
        if (name == null || name.isBlank()) {
            name = "OpenAI OAuth Account";
        }

        CreateAccountRequest createRequest = new CreateAccountRequest(
                name.trim(),
                null,
                "openai",
                "oauth",
                credentials,
                extra.isEmpty() ? null : extra,
                request.proxy_id(),
                request.concurrency(),
                null,
                request.priority(),
                null,
                request.group_ids() == null ? List.of() : request.group_ids(),
                null,
                null,
                null
        );
        return ApiResponse.success(adminAccountService.createAccount(createRequest));
    }
}
