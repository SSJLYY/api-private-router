package org.apiprivaterouter.javabackend.admin.account.model;

public record AccountRefreshWarningResponse(
        String message,
        String warning
) {
}
