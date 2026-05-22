package org.apiprivaterouter.javabackend.admin.proxy.model;

public record BatchCreateProxiesResponse(
        int created,
        int skipped
) {
}
