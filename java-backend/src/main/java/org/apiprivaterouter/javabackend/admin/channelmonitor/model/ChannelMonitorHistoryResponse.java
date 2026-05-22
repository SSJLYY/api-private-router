package org.apiprivaterouter.javabackend.admin.channelmonitor.model;

import java.util.List;

public record ChannelMonitorHistoryResponse(
        List<ChannelMonitorHistoryItemResponse> items
) {
}
