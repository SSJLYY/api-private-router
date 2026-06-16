package org.apiprivaterouter.javabackend.admin.apikey.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeletedApiKeyAuditResponse(
        long id,
        String key,
        long api_key_id,
        long user_id,
        String key_name,
        String deleted_at,
        String created_at
) {
}
