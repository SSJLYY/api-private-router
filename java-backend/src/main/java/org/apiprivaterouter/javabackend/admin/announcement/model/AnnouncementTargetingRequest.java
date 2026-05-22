package org.apiprivaterouter.javabackend.admin.announcement.model;

import java.util.List;

public record AnnouncementTargetingRequest(
        List<AnnouncementConditionGroupRequest> any_of
) {
}
