package org.apiprivaterouter.javabackend.admin.account.model;

public record TempUnschedulableStatusResponse(
        boolean active,
        TempUnschedulableStateResponse state
) {
}
