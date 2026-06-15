package org.apiprivaterouter.javabackend.userredpacket.model;

import java.math.BigDecimal;

public record ClaimRedpacketResponse(
    long redpacket_id,
    String code,
    BigDecimal amount,
    boolean is_best_luck,
    BigDecimal balance_before,
    BigDecimal balance_after,
    String claimed_at
) {}
