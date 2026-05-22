package org.apiprivaterouter.javabackend.channelmonitor.model;

import java.util.List;

public record UserMonitorDetailRecord(
        long id,
        String name,
        String provider,
        String groupName,
        List<UserMonitorModelDetail> models
) {
}
