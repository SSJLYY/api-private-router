package org.apiprivaterouter.javabackend.availablechannels.model;

import java.util.List;

public record UserChannelPlatformSectionResponse(
        String platform,
        List<UserAvailableGroupItemResponse> groups,
        List<UserSupportedModelResponse> supported_models
) {
}
