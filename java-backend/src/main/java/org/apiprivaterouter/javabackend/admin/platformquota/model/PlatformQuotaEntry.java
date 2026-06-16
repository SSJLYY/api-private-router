package org.apiprivaterouter.javabackend.admin.platformquota.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlatformQuotaEntry(
        String platform,
        BigDecimal daily_limit_usd,
        BigDecimal weekly_limit_usd,
        BigDecimal monthly_limit_usd
) {
}
