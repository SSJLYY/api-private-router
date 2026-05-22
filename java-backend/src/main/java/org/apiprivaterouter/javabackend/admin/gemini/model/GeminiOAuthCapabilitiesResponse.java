package org.apiprivaterouter.javabackend.admin.gemini.model;

import java.util.List;

public record GeminiOAuthCapabilitiesResponse(
        boolean ai_studio_oauth_enabled,
        List<String> required_redirect_uris
) {
}
