package org.apiprivaterouter.javabackend.gateway.controller;

import org.apiprivaterouter.javabackend.gateway.model.GatewayUsageResponse;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.service.GatewayRuntimeService;
import org.apiprivaterouter.javabackend.gateway.security.GatewayAccessPolicy;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyContextHolder;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal;
import org.apiprivaterouter.javabackend.gateway.service.GatewayUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping
public class GatewayUsageController {

    private final GatewayApiKeyContextHolder apiKeyContextHolder;
    private final GatewayAccessPolicy accessPolicy;
    private final GatewayRuntimeService runtimeService;
    private final GatewayUsageService usageService;

    public GatewayUsageController(
            GatewayApiKeyContextHolder apiKeyContextHolder,
            GatewayAccessPolicy accessPolicy,
            GatewayRuntimeService runtimeService,
            GatewayUsageService usageService
    ) {
        this.apiKeyContextHolder = apiKeyContextHolder;
        this.accessPolicy = accessPolicy;
        this.runtimeService = runtimeService;
        this.usageService = usageService;
    }

    @GetMapping("/v1/usage")
    public GatewayUsageResponse usage(
            HttpServletRequest request,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate
    ) {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, principal.groupPlatform());
        return usageService.buildUsageResponse(runtimeContext, startDate, endDate);
    }

    @GetMapping("/antigravity/v1/usage")
    public GatewayUsageResponse antigravityUsage(
            HttpServletRequest request,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate
    ) {
        GatewayApiKeyPrincipal principal = apiKeyContextHolder.requireApiKey();
        accessPolicy.requireGroupedAccess(principal, request);
        GatewayRuntimeContext runtimeContext = runtimeService.requireContext(principal, "antigravity");
        return usageService.buildUsageResponse(runtimeContext, startDate, endDate);
    }
}
