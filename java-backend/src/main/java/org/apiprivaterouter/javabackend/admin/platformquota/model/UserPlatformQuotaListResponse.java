package org.apiprivaterouter.javabackend.admin.platformquota.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserPlatformQuotaListResponse(
        List<UserPlatformQuotaResponse> quotas
) {
}
