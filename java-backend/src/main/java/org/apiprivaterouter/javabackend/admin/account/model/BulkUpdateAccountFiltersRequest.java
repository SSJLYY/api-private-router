package org.apiprivaterouter.javabackend.admin.account.model;

public record BulkUpdateAccountFiltersRequest(
        String platform,
        String type,
        String status,
        String group,
        String search,
        String privacy_mode
) {
}
