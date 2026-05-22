package org.apiprivaterouter.javabackend.admin.account.model;

public record CheckMixedChannelResponse(
        boolean has_risk,
        String error,
        String message,
        MixedChannelWarningDetailsResponse details
) {
}
