package org.apiprivaterouter.javabackend.riskcontrol.runtime.model;

public record ModerationApiKeyContext(
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
