package org.apiprivaterouter.javabackend.admin.account.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SyncFromCrsRequest(
        @NotBlank String base_url,
        @NotBlank String username,
        @NotBlank String password,
        Boolean sync_proxies,
        List<String> selected_account_ids
) {
}
