package org.apiprivaterouter.javabackend.admin.platformquota.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResetPlatformQuotaResponse(
        String platform,
        String window,
        BigDecimal usage_usd
) {
}
