package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;

public record BatchRefreshTierRequest(
        List<Long> account_ids
) {
}
