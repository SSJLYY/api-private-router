package org.apiprivaterouter.javabackend.admin.account.model;

public record BulkUpdateAccountResultResponse(
        long account_id,
        boolean success,
        String error
) {
}
