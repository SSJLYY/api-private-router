package org.apiprivaterouter.javabackend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "api-private-router.frontend")
public record FrontendAssetsProperties(
        String distDir
) {

    public String resolvedDistDir() {
        if (distDir == null || distDir.isBlank()) {
            return "";
        }
        return distDir.trim();
    }
}
