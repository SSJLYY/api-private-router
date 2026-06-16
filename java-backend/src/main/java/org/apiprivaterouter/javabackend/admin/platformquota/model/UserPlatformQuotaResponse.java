package org.apiprivaterouter.javabackend.admin.platformquota.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserPlatformQuotaResponse(
        long id,
        long user_id,
        String platform,
        BigDecimal daily_limit_usd,
        BigDecimal weekly_limit_usd,
        BigDecimal monthly_limit_usd,
        BigDecimal daily_usage_usd,
        BigDecimal weekly_usage_usd,
        BigDecimal monthly_usage_usd,
        String daily_window_start,
        String weekly_window_start,
        String monthly_window_start,
        String created_at,
        String updated_at
) {
}
