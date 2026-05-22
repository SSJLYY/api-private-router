package org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model;

import java.util.List;

public record ListChannelMonitorTemplatesResponse(
        List<ChannelMonitorTemplateResponse> items
) {
}
