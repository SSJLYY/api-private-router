package org.apiprivaterouter.javabackend.channelmonitor.model;

import java.util.List;
import java.util.Map;

public record ChannelMonitorWriteRequest(
        String name,
        String provider,
        String endpoint,
        String apiKeyPlaintext,
        String primaryModel,
        List<String> extraModels,
        String groupName,
        boolean enabled,
        int intervalSeconds,
        long createdBy,
        Long templateId,
        Map<String, String> extraHeaders,
        String bodyOverrideMode,
        Map<String, Object> bodyOverride
) {
}
