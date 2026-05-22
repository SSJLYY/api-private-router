package org.apiprivaterouter.javabackend.channelmonitor.model;

import java.util.List;

public record UserChannelMonitorListResponse(
        List<UserChannelMonitorResponse> items
) {
}
