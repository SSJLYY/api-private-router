package org.apiprivaterouter.javabackend.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayRuntimeService;
import org.apiprivaterouter.javabackend.gateway.security.GatewayAccessPolicy;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyContextHolder;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal;
import org.apiprivaterouter.javabackend.gateway.service.openai.OpenAiEmbeddingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class GatewayOpenAiEmbeddingsController {

    private final GatewayApiKeyContextHolder apiKeyContextHolder;
    private final GatewayAccessPolicy accessPolicy;
    private final GatewayRuntimeService runtimeService;
    private final OpenAiEmbeddingsService embeddingsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GatewayOpenAiEmbeddingsController(
            GatewayApiKeyContextHolder apiKeyContextHolder,
            GatewayAccessPolicy accessPolicy,
            GatewayRuntimeService runtimeService,
            OpenAiEmbeddingsService embeddingsService
    ) {
        this.apiKeyContextHolder = apiKeyContextHolder;
        this.accessPolicy = accessPolicy;
        this.runtimeService = runtimeService;
        this.embeddingsService = embeddingsService;
    }

    @PostMapping({"/v1/embeddings", "/embeddings"})
    public void embeddings(HttpServletRequest request, HttpServletResponse response, @RequestBody byte[] body) throws IOException {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);

        if (!"openai".equalsIgnoreCase(principal.groupPlatform())) {
            throw new OpenAiApiErrorException(404, "not_found_error", "Embeddings API is not supported for this platform");
        }

        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, "openai");
        if (runtimeContext.account() == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No available compatible OpenAI accounts");
        }

        String requestBody = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        JsonNode jsonBody = objectMapper.readTree(requestBody);
        String model = jsonBody.has("model") ? jsonBody.get("model").asText() : "text-embedding-3-small";

        embeddingsService.forwardEmbeddings(request, response, requestBody, model);
    }
}
