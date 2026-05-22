package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;

public record BatchRefreshRequest(
        List<Long> account_ids
) {
}
