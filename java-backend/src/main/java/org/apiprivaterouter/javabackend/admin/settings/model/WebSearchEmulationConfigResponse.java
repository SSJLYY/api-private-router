package org.apiprivaterouter.javabackend.admin.settings.model;

import java.util.List;

public record WebSearchEmulationConfigResponse(
        boolean enabled,
        List<WebSearchProviderConfig> providers
) {
}
