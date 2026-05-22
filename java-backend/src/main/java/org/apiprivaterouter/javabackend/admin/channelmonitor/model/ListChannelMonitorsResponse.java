package org.apiprivaterouter.javabackend.admin.channelmonitor.model;

import java.util.List;

public record ListChannelMonitorsResponse(
        List<ChannelMonitorResponse> items,
        long total,
        int page,
        int page_size,
        int pages
) {
}
