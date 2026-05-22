package org.apiprivaterouter.javabackend.admin.account.model;

import jakarta.validation.constraints.NotBlank;

public record PreviewFromCrsRequest(
        @NotBlank String base_url,
        @NotBlank String username,
        @NotBlank String password
) {
}
