package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;

public record BatchRefreshResultResponse(
        int total,
        int success,
        int failed,
        List<BatchOperationErrorResponse> errors,
        List<BatchRefreshWarningResponse> warnings
) {
}
