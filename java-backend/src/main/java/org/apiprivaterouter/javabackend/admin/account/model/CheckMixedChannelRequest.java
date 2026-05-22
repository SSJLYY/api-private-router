package org.apiprivaterouter.javabackend.admin.account.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CheckMixedChannelRequest(
        @NotBlank String platform,
        @NotNull List<Long> group_ids,
        Long account_id
) {
}
