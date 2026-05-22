package org.apiprivaterouter.javabackend.gateway.controller;

import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.gateway.model.ClaudeGatewayModelsResponse;
import org.apiprivaterouter.javabackend.gateway.model.GeminiGatewayModelResponse;
import org.apiprivaterouter.javabackend.gateway.model.GeminiGatewayModelsResponse;
import org.apiprivaterouter.javabackend.gateway.security.GatewayAccessPolicy;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyContextHolder;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal;
import org.apiprivaterouter.javabackend.gateway.service.GatewayModelsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping
public class GatewayModelsController {

    private final GatewayApiKeyContextHolder apiKeyContextHolder;
    private final GatewayAccessPolicy accessPolicy;
    private final GatewayModelsService gatewayModelsService;

    public GatewayModelsController(
            GatewayApiKeyContextHolder apiKeyContextHolder,
            GatewayAccessPolicy accessPolicy,
            GatewayModelsService gatewayModelsService
    ) {
        this.apiKeyContextHolder = apiKeyContextHolder;
        this.accessPolicy = accessPolicy;
        this.gatewayModelsService = gatewayModelsService;
    }

    @GetMapping("/v1/models")
    public ClaudeGatewayModelsResponse listModels(HttpServletRequest request) {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        return gatewayModelsService.buildClaudeModels(toCurrentUser(principal), principal.groupPlatform());
    }

    @GetMapping("/antigravity/models")
    public ClaudeGatewayModelsResponse listAntigravityModels(HttpServletRequest request) {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        return gatewayModelsService.buildAntigravityModels();
    }

    @GetMapping("/v1beta/models")
    public GeminiGatewayModelsResponse listGeminiModels(HttpServletRequest request) {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        accessPolicy.requirePlatform(principal, "gemini");
        return gatewayModelsService.buildGeminiFallbackModels(false);
    }

    @GetMapping("/v1beta/models/{model}")
    public GeminiGatewayModelResponse getGeminiModel(
            HttpServletRequest request,
            @PathVariable String model
    ) {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        accessPolicy.requirePlatform(principal, "gemini");
        return gatewayModelsService.buildGeminiFallbackModel(model, false);
    }

    @GetMapping("/antigravity/v1/models")
    public ClaudeGatewayModelsResponse listAntigravityV1Models(HttpServletRequest request) {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        return gatewayModelsService.buildAntigravityModels();
    }

    @GetMapping("/antigravity/v1beta/models")
    public GeminiGatewayModelsResponse listAntigravityGeminiModels(HttpServletRequest request) {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        return gatewayModelsService.buildGeminiFallbackModels(true);
    }

    @GetMapping("/antigravity/v1beta/models/{model}")
    public GeminiGatewayModelResponse getAntigravityGeminiModel(
            HttpServletRequest request,
            @PathVariable String model
    ) {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        return gatewayModelsService.buildGeminiFallbackModel(model, true);
    }

    private CurrentUser toCurrentUser(GatewayApiKeyPrincipal principal) {
        return new CurrentUser(principal.userId(), principal.userEmail(), principal.userRole(), 0);
    }
}
