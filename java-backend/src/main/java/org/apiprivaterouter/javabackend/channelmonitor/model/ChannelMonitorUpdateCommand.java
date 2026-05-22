package org.apiprivaterouter.javabackend.channelmonitor.model;

import java.util.List;
import java.util.Map;

public record ChannelMonitorUpdateCommand(
        String name,
        boolean namePresent,
        String provider,
        boolean providerPresent,
        String endpoint,
        boolean endpointPresent,
        String apiKeyPlaintext,
        boolean apiKeyPresent,
        String primaryModel,
        boolean primaryModelPresent,
        List<String> extraModels,
        boolean extraModelsPresent,
        String groupName,
        boolean groupNamePresent,
        Boolean enabled,
        boolean enabledPresent,
        Integer intervalSeconds,
        boolean intervalPresent,
        Long templateId,
        boolean templatePresent,
        boolean clearTemplate,
        Map<String, String> extraHeaders,
        boolean extraHeadersPresent,
        String bodyOverrideMode,
        boolean bodyOverrideModePresent,
        Map<String, Object> bodyOverride,
        boolean bodyOverridePresent
) {
}
