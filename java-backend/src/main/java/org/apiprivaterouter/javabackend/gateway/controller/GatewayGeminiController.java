package org.apiprivaterouter.javabackend.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.common.api.ApiErrorException;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayRuntimeService;
import org.apiprivaterouter.javabackend.gateway.security.GatewayAccessPolicy;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyContextHolder;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal;
import org.apiprivaterouter.javabackend.gateway.service.GatewayGeminiProxyService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

@RestController
@RequestMapping
public class GatewayGeminiController {

    private final GatewayApiKeyContextHolder apiKeyContextHolder;
    private final GatewayAccessPolicy accessPolicy;
    private final GatewayRuntimeService runtimeService;
    private final GatewayGeminiProxyService geminiProxyService;

    public GatewayGeminiController(
            GatewayApiKeyContextHolder apiKeyContextHolder,
            GatewayAccessPolicy accessPolicy,
            GatewayRuntimeService runtimeService,
            GatewayGeminiProxyService geminiProxyService
    ) {
        this.apiKeyContextHolder = apiKeyContextHolder;
        this.accessPolicy = accessPolicy;
        this.runtimeService = runtimeService;
        this.geminiProxyService = geminiProxyService;
    }

    @PostMapping("/v1beta/models/{model}:{action}")
    public void geminiModelAction(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String model,
            @PathVariable String action,
            @RequestBody byte[] body
    ) throws IOException {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        accessPolicy.requirePlatform(principal, "gemini");
        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, "gemini");
        validateAction(action);
        forwardWithJavaFailover(principal, runtimeContext, request, response, body, model, action, "gemini", false);
    }

    @PostMapping("/v1beta/models/{model}/{action}")
    public void geminiModelSlashAction(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String model,
            @PathVariable String action,
            @RequestBody byte[] body
    ) throws IOException {
        geminiModelAction(request, response, model, action, body);
    }

    @PostMapping("/antigravity/v1beta/models/{model}:{action}")
    public void antigravityGeminiModelAction(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String model,
            @PathVariable String action,
            @RequestBody byte[] body
    ) throws IOException {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, "antigravity");
        validateAction(action);
        forwardWithJavaFailover(principal, runtimeContext, request, response, body, model, action, "antigravity", true);
    }

    private void forwardWithJavaFailover(
            GatewayApiKeyPrincipal principal,
            GatewayRuntimeContext runtimeContext,
            HttpServletRequest request,
            HttpServletResponse response,
            byte[] body,
            String model,
            String action,
            String platformHint,
            boolean antigravityRoute
    ) throws IOException {
        Set<Long> attemptedAccountIds = new LinkedHashSet<>();
        GatewayRuntimeContext currentContext = runtimeContext;
        GatewayGeminiProxyService.RecoverableUpstreamException lastRecoverable = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (currentContext == null || currentContext.account() == null || !geminiProxyService.canHandle(currentContext, antigravityRoute)) {
                break;
            }
            attemptedAccountIds.add(currentContext.account().id());
            try {
                geminiProxyService.forward(currentContext, request, response, body, model, action);
                return;
            } catch (GatewayGeminiProxyService.RecoverableUpstreamException ex) {
                lastRecoverable = ex;
                currentContext = runtimeService.requireContextExcludingAccounts(
                        principal,
                        platformHint,
                        false,
                        attemptedAccountIds
                );
            }
        }
        if (lastRecoverable != null) {
            throw new ApiErrorException(lastRecoverable.getStatusCode(), "UNAVAILABLE", lastRecoverable.getMessage());
        }
        throw new ApiErrorException(503, "UNAVAILABLE", "No available Gemini accounts");
    }

    private void validateAction(String action) {
        if (!geminiProxyService.supportsAction(action)) {
            throw accessPolicy.googleError(404, "invalid model action path");
        }
    }

    @PostMapping("/antigravity/v1beta/models/{model}/{action}")
    public void antigravityGeminiModelSlashAction(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable String model,
            @PathVariable String action,
            @RequestBody byte[] body
    ) throws IOException {
        antigravityGeminiModelAction(request, response, model, action, body);
    }
}
