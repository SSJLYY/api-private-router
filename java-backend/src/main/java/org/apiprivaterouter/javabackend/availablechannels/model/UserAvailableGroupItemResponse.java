package org.apiprivaterouter.javabackend.availablechannels.model;

public record UserAvailableGroupItemResponse(
        long id,
        String name,
        String platform,
        String subscription_type,
        double rate_multiplier,
        boolean is_exclusive
) {
}
