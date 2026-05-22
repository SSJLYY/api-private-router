package org.apiprivaterouter.javabackend.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.common.api.AnthropicApiErrorException;
import org.apiprivaterouter.javabackend.common.api.OpenAiUpstreamFailoverException;
import org.apiprivaterouter.javabackend.gateway.model.ClaudeCountTokensResponse;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayRuntimeService;
import org.apiprivaterouter.javabackend.gateway.security.GatewayAccessPolicy;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyContextHolder;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal;
import org.apiprivaterouter.javabackend.gateway.service.GatewayAnthropicMessagesService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayCountTokensService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayGeminiMessagesCompatService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayGeminiProxyService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiMessagesDispatchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.Set;

@RestController
@RequestMapping
public class GatewayMessagesController {

    private final GatewayApiKeyContextHolder apiKeyContextHolder;
    private final GatewayAccessPolicy accessPolicy;
    private final GatewayRuntimeService runtimeService;
    private final GatewayAnthropicMessagesService messagesService;
    private final GatewayCountTokensService countTokensService;
    private final GatewayGeminiMessagesCompatService geminiMessagesCompatService;
    private final GatewayOpenAiMessagesDispatchService openAiMessagesDispatchService;
    private final ObjectMapper objectMapper;

    public GatewayMessagesController(
            GatewayApiKeyContextHolder apiKeyContextHolder,
            GatewayAccessPolicy accessPolicy,
            GatewayRuntimeService runtimeService,
            GatewayAnthropicMessagesService messagesService,
            GatewayCountTokensService countTokensService,
            GatewayGeminiMessagesCompatService geminiMessagesCompatService,
            GatewayOpenAiMessagesDispatchService openAiMessagesDispatchService,
            ObjectMapper objectMapper
    ) {
        this.apiKeyContextHolder = apiKeyContextHolder;
        this.accessPolicy = accessPolicy;
        this.runtimeService = runtimeService;
        this.messagesService = messagesService;
        this.countTokensService = countTokensService;
        this.geminiMessagesCompatService = geminiMessagesCompatService;
        this.openAiMessagesDispatchService = openAiMessagesDispatchService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/v1/messages")
    public void messages(HttpServletRequest request, HttpServletResponse response, @RequestBody byte[] body) throws java.io.IOException {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        if ("openai".equalsIgnoreCase(principal.groupPlatform())) {
            validateOpenAiMessagesPrecheck(principal, body);
            GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, "openai");
            if (runtimeContext.account() == null) {
                throw new AnthropicApiErrorException(503, "api_error", "No available OpenAI accounts");
            }
            try {
                openAiMessagesDispatchService.forward(runtimeContext, request, response, body);
            } catch (OpenAiUpstreamFailoverException ex) {
                GatewayRuntimeContext retryContext = runtimeService.requireContextExcludingAccount(
                        principal,
                        "openai",
                        false,
                        runtimeContext.account() == null ? null : runtimeContext.account().id()
                );
                if (retryContext.account() == null) {
                    throw ex.toAnthropicApiErrorException();
                }
                try {
                    openAiMessagesDispatchService.forward(retryContext, request, response, body);
                } catch (OpenAiUpstreamFailoverException retryEx) {
                    throw retryEx.toAnthropicApiErrorException();
                }
            }
            return;
        }
        String groupPlatform = principal.groupPlatform();
        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, groupPlatform);
        if ("anthropic".equalsIgnoreCase(groupPlatform) || "antigravity".equalsIgnoreCase(groupPlatform)) {
            messagesService.forward(runtimeContext, request, response, body);
            return;
        }
        if ("gemini".equalsIgnoreCase(groupPlatform)) {
            forwardGeminiCompatWithFailover(principal, runtimeContext, request, response, body);
            return;
        }
        if (!messagesService.canHandle(runtimeContext)) {
            throw new AnthropicApiErrorException(501, "unsupported_error", "Messages forwarding is not supported for this platform yet");
        }
        messagesService.forward(runtimeContext, request, response, body);
    }

    @PostMapping("/antigravity/v1/messages")
    public void antigravityMessages(HttpServletRequest request, HttpServletResponse response, @RequestBody byte[] body) throws java.io.IOException {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, "antigravity");
        messagesService.forward(runtimeContext, request, response, body);
    }

    @PostMapping("/v1/messages/count_tokens")
    public ClaudeCountTokensResponse countTokens(HttpServletRequest request, @RequestBody byte[] body) {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        if ("openai".equalsIgnoreCase(principal.groupPlatform())) {
            throw new AnthropicApiErrorException(404, "not_found_error", "Token counting is not supported for this platform");
        }
        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, principal.groupPlatform());
        return countTokensService.countTokens(runtimeContext, body, request.getRequestURI());
    }

    @PostMapping("/antigravity/v1/messages/count_tokens")
    public ClaudeCountTokensResponse antigravityCountTokens(HttpServletRequest request, @RequestBody byte[] body) {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, "antigravity");
        return countTokensService.countTokens(runtimeContext, body, request.getRequestURI());
    }

    private void validateOpenAiMessagesPrecheck(GatewayApiKeyPrincipal principal, byte[] body) {
        if (!principal.groupAllowMessagesDispatch()) {
            throw new AnthropicApiErrorException(403, "permission_error", "This group does not allow /v1/messages dispatch");
        }
        if (body == null || body.length == 0) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "Request body is empty");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "Failed to parse request body");
            }
            JsonNode modelNode = node.get("model");
            if (modelNode == null || !modelNode.isTextual() || modelNode.asText().isBlank()) {
                throw new AnthropicApiErrorException(400, "invalid_request_error", "model is required");
            }
        } catch (AnthropicApiErrorException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AnthropicApiErrorException(400, "invalid_request_error", "Failed to parse request body");
        }
    }

    private void forwardGeminiCompatWithFailover(
            GatewayApiKeyPrincipal principal,
            GatewayRuntimeContext runtimeContext,
            HttpServletRequest request,
            HttpServletResponse response,
            byte[] body
    ) {
        Set<Long> attemptedAccountIds = new LinkedHashSet<>();
        GatewayRuntimeContext currentContext = runtimeContext;
        GatewayGeminiProxyService.RecoverableUpstreamException lastRecoverable = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!geminiMessagesCompatService.canHandle(currentContext)) {
                break;
            }
            attemptedAccountIds.add(currentContext.account().id());
            try {
                geminiMessagesCompatService.forward(currentContext, request, response, body);
                return;
            } catch (GatewayGeminiProxyService.RecoverableUpstreamException ex) {
                lastRecoverable = ex;
                currentContext = runtimeService.requireContextExcludingAccounts(
                        principal,
                        "gemini",
                        false,
                        attemptedAccountIds
                );
            }
        }
        if (lastRecoverable != null) {
            throw new AnthropicApiErrorException(503, "api_error", lastRecoverable.getMessage());
        }
        throw new AnthropicApiErrorException(503, "api_error", "No available Gemini accounts");
    }
}
