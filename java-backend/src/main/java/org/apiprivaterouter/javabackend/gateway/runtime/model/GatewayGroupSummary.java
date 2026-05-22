package org.apiprivaterouter.javabackend.gateway.runtime.model;

import java.util.Map;

public record GatewayGroupSummary(
        long id,
        String name,
        String platform,
        String subscriptionType,
        Double dailyLimitUsd,
        Double weeklyLimitUsd,
        Double monthlyLimitUsd,
        boolean allowImageGeneration,
        boolean claudeCodeOnly,
        String defaultMappedModel,
        MessagesDispatchModelConfig messagesDispatchModelConfig
) {
    public record MessagesDispatchModelConfig(
            String opusMappedModel,
            String sonnetMappedModel,
            String haikuMappedModel,
            Map<String, String> exactModelMappings
    ) {
    }
}
