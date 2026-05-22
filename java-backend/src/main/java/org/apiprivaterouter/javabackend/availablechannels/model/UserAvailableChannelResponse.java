package org.apiprivaterouter.javabackend.availablechannels.model;

import java.util.List;

public record UserAvailableChannelResponse(
        String name,
        String description,
        List<UserChannelPlatformSectionResponse> platforms
) {
}
