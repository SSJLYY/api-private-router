package org.apiprivaterouter.javabackend.admin.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record BatchUpdateUserConcurrencyRequest(
        List<Long> user_ids,
        Boolean all,
        @Min(0) int concurrency,
        @NotBlank String mode
) {
}
