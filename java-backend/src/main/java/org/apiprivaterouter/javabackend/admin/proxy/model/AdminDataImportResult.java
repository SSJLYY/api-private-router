package org.apiprivaterouter.javabackend.admin.proxy.model;

import java.util.List;

public record AdminDataImportResult(
        int proxy_created,
        int proxy_reused,
        int proxy_failed,
        int account_created,
        int account_failed,
        List<AdminDataImportError> errors
) {
}
