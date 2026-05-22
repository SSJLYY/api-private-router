package org.apiprivaterouter.javabackend.admin.announcement.model;

import java.util.List;

public record AnnouncementConditionRequest(
        String type,
        String operator,
        List<Long> group_ids,
        Double value
) {
}
