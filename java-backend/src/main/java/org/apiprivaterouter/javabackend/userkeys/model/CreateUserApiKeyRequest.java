package org.apiprivaterouter.javabackend.userkeys.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateUserApiKeyRequest(
        @NotBlank String name,
        Long group_id,
        String custom_key,
        List<String> ip_whitelist,
        List<String> ip_blacklist,
        Double quota,
        Integer expires_in_days,
        Double rate_limit_5h,
        Double rate_limit_1d,
        Double rate_limit_7d
) {
}
