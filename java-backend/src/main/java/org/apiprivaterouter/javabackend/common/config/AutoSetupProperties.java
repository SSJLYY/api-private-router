package org.apiprivaterouter.javabackend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "api-private-router.auto-setup")
public record AutoSetupProperties(
        boolean enabled
) {
}
