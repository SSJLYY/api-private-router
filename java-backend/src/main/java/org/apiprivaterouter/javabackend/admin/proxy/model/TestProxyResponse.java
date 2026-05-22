package org.apiprivaterouter.javabackend.admin.proxy.model;

public record TestProxyResponse(
        boolean success,
        String message,
        Long latency_ms,
        String ip_address,
        String city,
        String region,
        String country,
        String country_code
) {
}
