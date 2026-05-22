package org.apiprivaterouter.javabackend.admin.group.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record UpdateGroupSortOrderRequest(
        @NotEmpty List<@Valid Entry> updates
) {
    public record Entry(
            @Positive long id,
            int sort_order
    ) {
    }
}
