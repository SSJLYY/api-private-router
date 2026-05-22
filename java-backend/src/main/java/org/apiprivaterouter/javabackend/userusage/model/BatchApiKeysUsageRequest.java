package org.apiprivaterouter.javabackend.userusage.model;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record BatchApiKeysUsageRequest(
        @NotNull List<Long> api_key_ids
) {
}
