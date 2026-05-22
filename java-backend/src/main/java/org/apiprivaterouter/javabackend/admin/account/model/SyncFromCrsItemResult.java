package org.apiprivaterouter.javabackend.admin.account.model;

public record SyncFromCrsItemResult(
        String crs_account_id,
        String kind,
        String name,
        String action,
        String error
) {
}
