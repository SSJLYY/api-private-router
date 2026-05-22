package org.apiprivaterouter.javabackend.admin.account.model;

public record BatchOperationErrorResponse(
        long account_id,
        String error
) {
}
