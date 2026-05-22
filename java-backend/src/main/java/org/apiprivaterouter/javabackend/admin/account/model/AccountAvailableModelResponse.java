package org.apiprivaterouter.javabackend.admin.account.model;

public record AccountAvailableModelResponse(
        String id,
        String type,
        String display_name,
        String created_at
) {
}
