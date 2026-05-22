package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;

public record BatchOperationResultResponse(
        int total,
        int success,
        int failed,
        List<BatchOperationErrorResponse> errors
) {
}
