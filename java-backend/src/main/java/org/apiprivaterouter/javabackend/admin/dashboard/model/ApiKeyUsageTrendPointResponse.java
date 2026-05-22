package org.apiprivaterouter.javabackend.admin.dashboard.model;

public record ApiKeyUsageTrendPointResponse(
        String date,
        long api_key_id,
        String key_name,
        long requests,
        long tokens
) {
}
