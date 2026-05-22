package org.apiprivaterouter.javabackend.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "api-private-router.jwt")
public record JwtProperties(
        String secret,
        Integer accessTokenExpireMinutes,
        Integer expireHour,
        Integer refreshTokenExpireDays
) {
}
