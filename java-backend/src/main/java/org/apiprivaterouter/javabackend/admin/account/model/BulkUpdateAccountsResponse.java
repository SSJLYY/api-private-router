package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;

public record BulkUpdateAccountsResponse(
        int success,
        int failed,
        List<Long> success_ids,
        List<Long> failed_ids,
        List<BulkUpdateAccountResultResponse> results
) {
}
