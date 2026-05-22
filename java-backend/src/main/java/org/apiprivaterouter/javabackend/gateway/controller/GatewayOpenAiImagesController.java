package org.apiprivaterouter.javabackend.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apiprivaterouter.javabackend.common.api.OpenAiApiErrorException;
import org.apiprivaterouter.javabackend.common.api.OpenAiUpstreamFailoverException;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayRuntimeService;
import org.apiprivaterouter.javabackend.gateway.security.GatewayAccessPolicy;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyContextHolder;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal;
import org.apiprivaterouter.javabackend.gateway.service.GatewayOpenAiImagesService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping
public class GatewayOpenAiImagesController {

    private final GatewayApiKeyContextHolder apiKeyContextHolder;
    private final GatewayAccessPolicy accessPolicy;
    private final GatewayRuntimeService runtimeService;
    private final GatewayOpenAiImagesService imagesService;

    public GatewayOpenAiImagesController(
            GatewayApiKeyContextHolder apiKeyContextHolder,
            GatewayAccessPolicy accessPolicy,
            GatewayRuntimeService runtimeService,
            GatewayOpenAiImagesService imagesService
    ) {
        this.apiKeyContextHolder = apiKeyContextHolder;
        this.accessPolicy = accessPolicy;
        this.runtimeService = runtimeService;
        this.imagesService = imagesService;
    }

    @PostMapping({"/v1/images/generations", "/openai/v1/images/generations", "/images/generations"})
    public void generations(HttpServletRequest request, HttpServletResponse response, @RequestBody byte[] body) throws IOException {
        forward(request, response, body);
    }

    @PostMapping({"/v1/images/edits", "/openai/v1/images/edits", "/images/edits"})
    public void edits(HttpServletRequest request, HttpServletResponse response, @RequestBody byte[] body) throws IOException {
        forward(request, response, body);
    }

    private void forward(HttpServletRequest request, HttpServletResponse response, byte[] body) throws IOException {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        if (!"openai".equalsIgnoreCase(principal.groupPlatform())) {
            throw new OpenAiApiErrorException(404, "not_found_error", "Images API is not supported for this platform");
        }
        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, "openai");
        if (runtimeContext.account() == null) {
            throw new OpenAiApiErrorException(503, "api_error", "No available compatible OpenAI accounts");
        }
        try {
            imagesService.forward(runtimeContext, request, response, body);
        } catch (OpenAiUpstreamFailoverException ex) {
            GatewayRuntimeContext retryContext = runtimeService.requireContextExcludingAccount(
                    principal,
                    "openai",
                    false,
                    runtimeContext.account() == null ? null : runtimeContext.account().id()
            );
            if (retryContext.account() == null) {
                throw ex.toOpenAiApiErrorException();
            }
            try {
                imagesService.forward(retryContext, request, response, body);
            } catch (OpenAiUpstreamFailoverException retryEx) {
                throw retryEx.toOpenAiApiErrorException();
            }
        }
    }
}
