package org.apiprivaterouter.javabackend.gateway.runtime.service;

import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayApiKeyRuntimeView;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayRuntimeContext;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewaySubscriptionSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.model.GatewayUserSummary;
import org.apiprivaterouter.javabackend.gateway.runtime.repository.GatewayRuntimeRepository;
import org.apiprivaterouter.javabackend.gateway.security.GatewayApiKeyPrincipal;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
public class GatewayRuntimeService {

    private final GatewayRuntimeRepository repository;

    public GatewayRuntimeService(GatewayRuntimeRepository repository) {
        this.repository = repository;
    }

    public GatewayRuntimeContext requireContext(GatewayApiKeyPrincipal principal, String platformHint) {
        return requireContext(principal, platformHint, false);
    }

    public GatewayRuntimeContext requireContext(GatewayApiKeyPrincipal principal, String platformHint, boolean requireCompact) {
        return requireContext(principal, platformHint, requireCompact, java.util.List.of());
    }

    public GatewayRuntimeContext requireContextExcludingAccount(
            GatewayApiKeyPrincipal principal,
            String platformHint,
            boolean requireCompact,
            Long excludedAccountId
    ) {
        return requireContext(principal, platformHint, requireCompact, excludedAccountId);
    }

    public GatewayRuntimeContext requireContextExcludingAccounts(
            GatewayApiKeyPrincipal principal,
            String platformHint,
            boolean requireCompact,
            Collection<Long> excludedAccountIds
    ) {
        return requireContext(principal, platformHint, requireCompact, excludedAccountIds);
    }

    public GatewayRuntimeContext requireContextForAccount(
            GatewayApiKeyPrincipal principal,
            String platformHint,
            boolean requireCompact,
            long accountId
    ) {
        GatewayApiKeyRuntimeView apiKey = repository.findApiKey(principal.apiKeyId())
                .orElseThrow(() -> new HttpStatusException(401, "invalid api key"));
        GatewayUserSummary user = repository.findUser(principal.userId())
                .orElseThrow(() -> new HttpStatusException(401, "invalid api key"));

        GatewaySubscriptionSummary subscription = null;
        if (apiKey.group() != null && "subscription".equalsIgnoreCase(apiKey.group().subscriptionType())) {
            subscription = repository.findActiveSubscription(apiKey.userId(), apiKey.group().id()).orElse(null);
        }

        return new GatewayRuntimeContext(
                apiKey,
                user,
                subscription,
                repository.findAccountForGroup(
                        apiKey.groupId(),
                        accountId,
                        effectivePlatform(platformHint, apiKey),
                        requireCompact
                ).orElse(null)
        );
    }

    private GatewayRuntimeContext requireContext(
            GatewayApiKeyPrincipal principal,
            String platformHint,
            boolean requireCompact,
            Long excludedAccountId
    ) {
        return requireContext(principal, platformHint, requireCompact, excludedAccountId == null ? java.util.List.of() : java.util.List.of(excludedAccountId));
    }

    private GatewayRuntimeContext requireContext(
            GatewayApiKeyPrincipal principal,
            String platformHint,
            boolean requireCompact,
            Collection<Long> excludedAccountIds
    ) {
        GatewayApiKeyRuntimeView apiKey = repository.findApiKey(principal.apiKeyId())
                .orElseThrow(() -> new HttpStatusException(401, "invalid api key"));
        GatewayUserSummary user = repository.findUser(principal.userId())
                .orElseThrow(() -> new HttpStatusException(401, "invalid api key"));

        GatewaySubscriptionSummary subscription = null;
        if (apiKey.group() != null && "subscription".equalsIgnoreCase(apiKey.group().subscriptionType())) {
            subscription = repository.findActiveSubscription(apiKey.userId(), apiKey.group().id()).orElse(null);
        }

        return new GatewayRuntimeContext(
                apiKey,
                user,
                subscription,
                repository.findPreferredAccount(
                        apiKey.groupId(),
                        effectivePlatform(platformHint, apiKey),
                        requireCompact,
                        excludedAccountIds
                ).orElse(null)
        );
    }

    private String effectivePlatform(String platformHint, GatewayApiKeyRuntimeView apiKey) {
        if (platformHint != null && !platformHint.isBlank()) {
            return platformHint.trim().toLowerCase();
        }
        return apiKey.group() == null ? "" : apiKey.group().platform();
    }
}
