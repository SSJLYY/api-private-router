package org.apiprivaterouter.javabackend.admin.account.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerateAuthUrlRequest(
        Long proxy_id
) {
}
