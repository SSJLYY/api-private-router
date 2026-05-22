package org.apiprivaterouter.javabackend.admin.account.model;

public record BatchCreateAccountResultResponse(
        String name,
        Long id,
        boolean success,
        String error
) {
}
