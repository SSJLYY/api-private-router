package org.apiprivaterouter.javabackend.userbindings.model;

public record StartIdentityBindingResponse(
        String provider,
        String authorize_url,
        String method,
        boolean use_browser_redirect
) {
}
