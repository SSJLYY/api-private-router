package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;

public record BatchCreateAccountsResponse(
        int success,
        int failed,
        List<BatchCreateAccountResultResponse> results
) {
}
