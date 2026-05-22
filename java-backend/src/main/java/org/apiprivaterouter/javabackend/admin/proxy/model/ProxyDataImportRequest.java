package org.apiprivaterouter.javabackend.admin.proxy.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record ProxyDataImportRequest(
        @NotNull @Valid AdminDataPayload data
) {
}
