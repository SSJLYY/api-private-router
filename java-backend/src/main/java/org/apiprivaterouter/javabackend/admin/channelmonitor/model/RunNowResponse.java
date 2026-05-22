package org.apiprivaterouter.javabackend.admin.channelmonitor.model;

import java.util.List;

public record RunNowResponse(
        List<ChannelMonitorCheckResultResponse> results
) {
}
