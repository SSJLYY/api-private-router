package org.apiprivaterouter.javabackend.gateway.security;

public record GatewayApiKeyPrincipal(
        long apiKeyId,
        String apiKeyName,
        String apiKeyStatus,
        long userId,
        String userEmail,
        String userStatus,
        int userConcurrency,
        String userRole,
        Long groupId,
        String groupName,
        String groupPlatform,
        String groupSubscriptionType,
        boolean groupAllowImageGeneration,
        boolean groupAllowMessagesDispatch,
        boolean groupClaudeCodeOnly
) {
}
