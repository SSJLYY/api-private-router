package org.apiprivaterouter.javabackend.admin.account.model;

public record CrsPreviewAccount(
        String crs_account_id,
        String kind,
        String name,
        String platform,
        String type
) {
}
