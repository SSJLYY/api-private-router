package org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model;

public record AssociatedMonitorBriefResponse(
        long id,
        String name,
        String provider,
        boolean enabled
) {
}
