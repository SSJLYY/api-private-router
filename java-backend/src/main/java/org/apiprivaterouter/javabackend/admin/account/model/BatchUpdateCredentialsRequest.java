package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;

public record BatchUpdateCredentialsRequest(
        List<Long> account_ids,
        String field,
        Object value
) {
}
