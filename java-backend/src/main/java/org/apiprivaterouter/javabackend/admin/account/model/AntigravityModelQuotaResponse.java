package org.apiprivaterouter.javabackend.admin.account.model;

public record AntigravityModelQuotaResponse(
        int utilization,
        String reset_time
) {
}
