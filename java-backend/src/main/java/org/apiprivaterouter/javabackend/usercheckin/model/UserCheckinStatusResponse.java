package org.apiprivaterouter.javabackend.usercheckin.model;

import java.math.BigDecimal;

public record UserCheckinStatusResponse(
        String checkin_date,
        String timezone,
        boolean checked_in,
        BigDecimal latest_multiplier,
        BigDecimal latest_reward_amount,
        BigDecimal latest_net_change,
        BigDecimal balance,
        String checked_in_at
) {
}
