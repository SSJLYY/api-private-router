package org.apiprivaterouter.javabackend.admin.proxy.model;

import java.util.List;
import java.util.Map;

public record AdminDataPayload(
        String type,
        Integer version,
        String exported_at,
        List<AdminDataProxy> proxies,
        List<Map<String, Object>> accounts
) {
}
