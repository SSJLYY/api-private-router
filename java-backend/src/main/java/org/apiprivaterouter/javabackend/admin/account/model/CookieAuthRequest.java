package org.apiprivaterouter.javabackend.admin.account.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CookieAuthRequest(
        @NotBlank String code,
        Long proxy_id
) {
}
