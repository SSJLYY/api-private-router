package org.apiprivaterouter.javabackend.admin.system.model;

public record SystemReleaseAssetResponse(
        String name,
        String download_url,
        long size
) {
}
