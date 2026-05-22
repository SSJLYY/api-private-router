package org.apiprivaterouter.javabackend.gateway.runtime.model;

import java.util.Map;

public record GatewayAccountSummary(
        long id,
        String name,
        String platform,
        String type,
        String status,
        Integer priority,
        Long proxyId,
        Map<String, Object> credentials,
        Map<String, Object> extra
) {
}
