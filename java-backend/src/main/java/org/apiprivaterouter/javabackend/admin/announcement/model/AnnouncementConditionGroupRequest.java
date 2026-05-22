package org.apiprivaterouter.javabackend.admin.announcement.model;

import java.util.List;

public record AnnouncementConditionGroupRequest(
        List<AnnouncementConditionRequest> all_of
) {
}
