package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;

public record AdminDataPayload(
        String type,
        Integer version,
        String exported_at,
        List<AdminDataProxy> proxies,
        List<AdminDataAccount> accounts
) {
}
