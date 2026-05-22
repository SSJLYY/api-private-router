package org.apiprivaterouter.javabackend.admin.proxy.model;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchDeleteProxiesRequest(
        @NotEmpty List<Long> ids
) {
}
