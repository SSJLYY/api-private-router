package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;

public record PreviewFromCrsResult(
        List<CrsPreviewAccount> new_accounts,
        List<CrsPreviewAccount> existing_accounts
) {
}
