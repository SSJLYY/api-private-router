package org.apiprivaterouter.javabackend.admin.proxy.model;

public record AdminProxyResponse(
        long id,
        String name,
        String protocol,
        String host,
        int port,
        String username,
        String password,
        String status,
        Long account_count,
        Long latency_ms,
        String latency_status,
        String latency_message,
        String ip_address,
        String country,
        String country_code,
        String region,
        String city,
        String quality_status,
        Integer quality_score,
        String quality_grade,
        String quality_summary,
        Long quality_checked,
        String created_at,
        String updated_at
) {
}
