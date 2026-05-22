package org.apiprivaterouter.javabackend.usercheckin.model;

import java.util.List;

public record UserCheckinCalendarResponse(
        int year,
        int month,
        String timezone,
        List<UserCheckinCalendarDayResponse> days
) {
}
