package org.apiprivaterouter.javabackend.gateway.service.openai;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class OpenAiAllowedClientService {

    private static final Map<String, AllowedClientEntry> REGISTRY = Map.of(
            "claude_code", new AllowedClientEntry("Claude Code", List.of("Claude Code/"))
    );

    public boolean matchAllowedClients(String userAgent, String originator, List<String> clientIds) {
        if (clientIds == null || clientIds.isEmpty()) {
            return false;
        }
        for (String clientId : clientIds) {
            AllowedClientEntry entry = REGISTRY.get(clientId);
            if (entry == null) {
                continue;
            }
            if (isAllowedClientMatch(userAgent, originator, entry)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowedClientMatch(String userAgent, String originator, AllowedClientEntry entry) {
        if (entry.uaContains() == null || entry.uaContains().isEmpty()) {
            return false;
        }
        boolean originatorMatch = entry.originator() == null ||
                entry.originator().equalsIgnoreCase(originator != null ? originator : "");
        if (!originatorMatch) {
            return false;
        }
        String normalizedUA = userAgent != null ? userAgent : "";
        for (String marker : entry.uaContains()) {
            if (!normalizedUA.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    record AllowedClientEntry(String originator, List<String> uaContains) {
    }
}
