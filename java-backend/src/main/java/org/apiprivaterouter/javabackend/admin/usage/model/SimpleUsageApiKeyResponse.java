package org.apiprivaterouter.javabackend.admin.usage.model;

public record SimpleUsageApiKeyResponse(
        long id,
        String name,
        long user_id
) {
}
