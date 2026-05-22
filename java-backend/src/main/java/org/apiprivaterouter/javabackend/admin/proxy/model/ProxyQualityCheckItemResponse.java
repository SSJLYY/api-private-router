package org.apiprivaterouter.javabackend.admin.proxy.model;

public record ProxyQualityCheckItemResponse(
        String target,
        String status,
        Integer http_status,
        Long latency_ms,
        String message,
        String cf_ray
) {
}
