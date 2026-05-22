package org.apiprivaterouter.javabackend.admin.proxy.model;

public record BatchDeleteSkippedResponse(
        long id,
        String reason
) {
}
