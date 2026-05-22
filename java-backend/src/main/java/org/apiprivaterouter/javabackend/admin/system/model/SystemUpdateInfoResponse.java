package org.apiprivaterouter.javabackend.admin.system.model;

public record SystemUpdateInfoResponse(
        String current_version,
        String latest_version,
        boolean has_update,
        SystemReleaseInfoResponse release_info,
        boolean cached,
        String warning,
        String build_type
) {
}
