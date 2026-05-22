package org.apiprivaterouter.javabackend.admin.account.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record AccountDataImportRequest(
        @NotNull @Valid AdminDataPayload data,
        Boolean skip_default_group_bind
) {
}
