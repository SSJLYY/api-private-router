package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;

public record BatchClearErrorRequest(
        List<Long> account_ids
) {
}
