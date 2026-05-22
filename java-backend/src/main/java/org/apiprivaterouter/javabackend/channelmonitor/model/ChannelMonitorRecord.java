package org.apiprivaterouter.javabackend.channelmonitor.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ChannelMonitorRecord(
        long id,
        String name,
        String provider,
        String endpoint,
        String apiKeyEncrypted,
        String primaryModel,
        List<String> extraModels,
        String groupName,
        boolean enabled,
        int intervalSeconds,
        Instant lastCheckedAt,
        long createdBy,
        Instant createdAt,
        Instant updatedAt,
        Long templateId,
        Map<String, String> extraHeaders,
        String bodyOverrideMode,
        Map<String, Object> bodyOverride
) {
}
