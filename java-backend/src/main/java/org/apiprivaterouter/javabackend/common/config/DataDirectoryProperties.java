package org.apiprivaterouter.javabackend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "api-private-router.data")
public record DataDirectoryProperties(
        String dir
) {

    public String resolvedDir() {
        if (dir == null || dir.isBlank()) {
            return "data";
        }
        return dir.trim();
    }
}
