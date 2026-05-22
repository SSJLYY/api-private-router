package org.apiprivaterouter.javabackend.admin.proxy.model;

public record ProxyAccountSummaryResponse(
        long id,
        String name,
        String platform,
        String type,
        String notes
) {
}
