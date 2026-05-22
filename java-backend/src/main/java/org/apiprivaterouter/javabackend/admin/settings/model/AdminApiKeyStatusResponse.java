package org.apiprivaterouter.javabackend.admin.settings.model;

public record AdminApiKeyStatusResponse(
        boolean exists,
        String masked_key
) {
}
