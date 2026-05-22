package org.apiprivaterouter.javabackend.usercheckin.model;

import java.math.BigDecimal;

public record UserCheckinHistoryItemResponse(
        long id,
        String checkin_date,
        String timezone,
        BigDecimal stake_amount,
        BigDecimal reward_amount,
        BigDecimal multiplier,
        BigDecimal net_change,
        BigDecimal balance_before,
        BigDecimal balance_after,
        String checked_in_at
) {
}
