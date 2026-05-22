package org.apiprivaterouter.javabackend.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException;
import org.apiprivaterouter.javabackend.common.api.OpenAiUpstreamFailoverException;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayOpenAiAccountRoutingPolicy;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayRuntimeService;
import org.apiprivaterouter.javabackend.gateway.security.GatewayAccessPolicy;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyContextHolder;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal;
import org.apiprivaterouter.javabackend.gateway.service.GatewayAnthropicResponsesService;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiResponsesService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping
public class GatewayOpenAiResponsesController {

    private final GatewayApiKeyContextHolder apiKeyContextHolder;
    private final GatewayAccessPolicy accessPolicy;
    private final GatewayRuntimeService runtimeService;
    private final GatewayOpenAiAccountRoutingPolicy routingPolicy;
    private final GatewayOpenAiResponsesService responsesService;
    private final GatewayAnthropicResponsesService anthropicResponsesService;

    public GatewayOpenAiResponsesController(
            GatewayApiKeyContextHolder apiKeyContextHolder,
            GatewayAccessPolicy accessPolicy,
            GatewayRuntimeService runtimeService,
            GatewayOpenAiAccountRoutingPolicy routingPolicy,
            GatewayOpenAiResponsesService responsesService,
            GatewayAnthropicResponsesService anthropicResponsesService
    ) {
        this.apiKeyContextHolder = apiKeyContextHolder;
        this.accessPolicy = accessPolicy;
        this.runtimeService = runtimeService;
        this.routingPolicy = routingPolicy;
        this.responsesService = responsesService;
        this.anthropicResponsesService = anthropicResponsesService;
    }

    @PostMapping({
            "/v1/responses",
            "/responses",
            "/openai/v1/responses",
            "/backend-api/codex/responses"
    })
    public void responses(HttpServletRequest request, HttpServletResponse response, @RequestBody byte[] body) throws IOException {
        forward(request, response, body);
    }

    @PostMapping({
            "/v1/responses/{*subpath}",
            "/responses/{*subpath}",
            "/openai/v1/responses/{*subpath}",
            "/backend-api/codex/responses/{*subpath}"
    })
    public void responsesSubpath(HttpServletRequest request, HttpServletResponse response, @RequestBody byte[] body) throws IOException {
        forward(request, response, body);
    }

    @RequestMapping(
            value = {
                    "/v1/responses",
                    "/responses",
                    "/openai/v1/responses",
                    "/backend-api/codex/responses",
                    "/v1/responses/{*subpath}",
                    "/responses/{*subpath}",
                    "/openai/v1/responses/{*subpath}",
                    "/backend-api/codex/responses/{*subpath}"
            },
            method = {RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.PUT}
    )
    public void unsupportedMutationRoute() {
        throw new OpenAiApiErrorException(501, "unsupported_error", "This Responses API HTTP method is not supported");
    }

    @RequestMapping(
            value = {
                    "/v1/responses/{*subpath}",
                    "/responses/{*subpath}",
                    "/openai/v1/responses/{*subpath}",
                    "/backend-api/codex/responses/{*subpath}"
            },
            method = RequestMethod.GET,
            headers = "!Sec-WebSocket-Key"
    )
    public void unsupportedGetSubpathRoute() {
        throw new OpenAiApiErrorException(501, "unsupported_error", "This Responses API subpath is not supported for GET");
    }

    private void forward(HttpServletRequest request, HttpServletResponse response, byte[] body) throws IOException {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        if (principal.groupClaudeCodeOnly()) {
            throw new OpenAiApiErrorException(403, "permission_error", "This group is restricted to Claude Code clients (/v1/messages only)");
        }
        if ("openai".equalsIgnoreCase(principal.groupPlatform())) {
            forwardOpenAi(principal, request, response, body);
            return;
        }
        if (!anthropicResponsesService.supportsPlatform(principal.groupPlatform())) {
            throw new OpenAiApiErrorException(404, "not_found_error", "Responses API is not supported for this platform");
        }
        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, principal.groupPlatform());
        if (runtimeContext.account() == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No available compatible accounts");
        }
        try {
            anthropicResponsesService.forward(runtimeContext, request, response, body);
        } catch (OpenAiUpstreamFailoverException ex) {
            GatewayRuntimeContext retryContext = runtimeService.requireContextExcludingAccount(
                    principal,
                    principal.groupPlatform(),
                    false,
                    runtimeContext.account() == null ? null : runtimeContext.account().id()
            );
            if (retryContext.account() == null) {
                throw ex.toOpenAiApiErrorException();
            }
            try {
                anthropicResponsesService.forward(retryContext, request, response, body);
            } catch (OpenAiUpstreamFailoverException retryEx) {
                throw retryEx.toOpenAiApiErrorException();
            }
        }
    }

    private void forwardOpenAi(
            GatewayApiKeyPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response,
            byte[] body
    ) throws IOException {
        boolean compactRequest = isCompactRequest(request);
        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, "openai", compactRequest);
        if (runtimeContext.account() == null) {
            throw compactRequest
                    ? new OpenAiApiErrorException(503, "compact_not_supported", "No available OpenAI accounts support /responses/compact")
                    : new OpenAiApiErrorException(503, "api_error", "No available compatible OpenAI accounts");
        }
        if (isCodexCliOnlyRejected(runtimeContext, request)) {
            throw new OpenAiApiErrorException(403, "forbidden_error", "Only official Codex clients are allowed for this account");
        }
        if (!routingPolicy.canHandleResponsesHttp(runtimeContext)) {
            throw new OpenAiApiErrorException(501, "unsupported_error", "Responses forwarding is not supported for this account type yet");
        }
        try {
            responsesService.forward(runtimeContext, request, response, body);
        } catch (OpenAiUpstreamFailoverException ex) {
            GatewayRuntimeContext retryContext = runtimeService.requireContextExcludingAccount(
                    principal,
                    "openai",
                    compactRequest,
                    runtimeContext.account() == null ? null : runtimeContext.account().id()
            );
            if (retryContext.account() == null) {
                throw ex.toOpenAiApiErrorException();
            }
            if (isCodexCliOnlyRejected(retryContext, request)) {
                throw ex.toOpenAiApiErrorException();
            }
            if (!routingPolicy.canHandleResponsesHttp(retryContext)) {
                throw ex.toOpenAiApiErrorException();
            }
            try {
                responsesService.forward(retryContext, request, response, body);
            } catch (OpenAiUpstreamFailoverException retryEx) {
                throw retryEx.toOpenAiApiErrorException();
            }
        }
    }

    private boolean isCompactRequest(HttpServletRequest request) {
        if (request == null || request.getRequestURI() == null) {
            return false;
        }
        String normalized = request.getRequestURI().trim().toLowerCase();
        return normalized.contains("/responses/compact");
    }

    private boolean isCodexCliOnlyRejected(GatewayRuntimeContext runtimeContext, HttpServletRequest request) {
        if (runtimeContext == null || runtimeContext.account() == null) {
            return false;
        }
        Map<String, Object> extra = runtimeContext.account().extra();
        if (!isTrue(extra == null ? null : extra.get("codex_cli_only"))) {
            return false;
        }
        return !isOfficialCodexClient(request);
    }

    private boolean isOfficialCodexClient(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        return matchesOfficialCodexHeader(request.getHeader("User-Agent"))
                || matchesOfficialCodexHeader(request.getHeader("Originator"));
    }

    private boolean matchesOfficialCodexHeader(String value) {
        String normalized = normalizeHeader(value);
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.startsWith("codex_")
                || normalized.startsWith("codex ")
                || normalized.contains("codex_vscode/")
                || normalized.contains("codex_cli_rs/")
                || normalized.contains("codex_app/")
                || normalized.contains("codex_chatgpt_desktop/")
                || normalized.contains("codex_atlas/")
                || normalized.contains("codex_exec/")
                || normalized.contains("codex_sdk_ts/")
                || normalized.contains("codex desktop");
    }

    private boolean isTrue(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return "true".equalsIgnoreCase(text.trim());
        }
        return false;
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

}
