package org.apiprivaterouter.javabackend.admin.account.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record CreateAccountRequest(
        @NotBlank String name,
        String notes,
        @NotBlank String platform,
        @NotBlank String type,
        @NotNull @NotEmpty Map<String, Object> credentials,
        Map<String, Object> extra,
        Long proxy_id,
        Integer concurrency,
        Integer load_factor,
        Integer priority,
        Double rate_multiplier,
        List<Long> group_ids,
        Long expires_at,
        Boolean auto_pause_on_expired,
        Boolean confirm_mixed_channel_risk
) {
}
