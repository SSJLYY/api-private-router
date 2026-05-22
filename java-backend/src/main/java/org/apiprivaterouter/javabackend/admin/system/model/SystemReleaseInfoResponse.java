package org.apiprivaterouter.javabackend.admin.system.model;

import java.util.List;

public record SystemReleaseInfoResponse(
        String name,
        String body,
        String published_at,
        String html_url,
        List<SystemReleaseAssetResponse> assets
) {
}
