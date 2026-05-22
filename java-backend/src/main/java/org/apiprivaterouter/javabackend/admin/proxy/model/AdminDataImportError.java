package org.apiprivaterouter.javabackend.admin.proxy.model;

public record AdminDataImportError(
        String kind,
        String name,
        String proxy_key,
        String message
) {
}
