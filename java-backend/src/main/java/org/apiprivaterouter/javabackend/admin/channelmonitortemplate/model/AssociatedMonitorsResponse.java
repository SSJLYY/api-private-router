package org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model;

import java.util.List;

public record AssociatedMonitorsResponse(
        List<AssociatedMonitorBriefResponse> items
) {
}
