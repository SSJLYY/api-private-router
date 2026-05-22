package org.apiprivaterouter.javabackend.admin.group.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record BatchSetGroupRpmOverridesRequest(
        @NotEmpty List<@Valid Entry> entries
) {
    public record Entry(
            @Positive long user_id,
            @Min(0) Integer rpm_override
    ) {
    }
}
