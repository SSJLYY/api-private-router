package org.apiprivaterouter.javabackend.channelmonitor.model;

import java.util.List;

public record UserChannelMonitorDetailResponse(
        long id,
        String name,
        String provider,
        String group_name,
        List<UserChannelMonitorModelDetailResponse> models
) {
}
