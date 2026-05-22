package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;

public record SyncFromCrsResult(
        int created,
        int updated,
        int skipped,
        int failed,
        List<SyncFromCrsItemResult> items
) {
}
