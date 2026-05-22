package org.apiprivaterouter.javabackend.admin.antigravity.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AntigravityRefreshTokenRequest(
        String refresh_token,
        Long proxy_id
) {
}
