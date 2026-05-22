package org.apiprivaterouter.javabackend.usertotp.model;

public record TotpStatusResponse(
        boolean enabled,
        Long enabled_at,
        boolean feature_enabled
) {
}
