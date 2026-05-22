package org.apiprivaterouter.javabackend.usercheckin.model;

import java.math.BigDecimal;

public record UserCheckinCalendarDayResponse(
        String checkin_date,
        boolean checked_in,
        BigDecimal stake_amount,
        BigDecimal reward_amount,
        BigDecimal multiplier,
        BigDecimal net_change,
        String checked_in_at
) {
}
