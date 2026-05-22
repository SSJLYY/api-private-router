package org.apiprivaterouter.javabackend.admin.antigravity.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AntigravityAuthUrlRequest(
        Long proxy_id
) {
}
