package org.apiprivaterouter.javabackend.admin.account.model;

public record BatchRefreshWarningResponse(
        long account_id,
        String warning
) {
}
