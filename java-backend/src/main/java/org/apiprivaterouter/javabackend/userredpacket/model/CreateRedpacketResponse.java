package org.apiprivaterouter.javabackend.userredpacket.model;

import java.math.BigDecimal;

public record CreateRedpacketResponse(
    long id,
    String code,
    String redpacket_type,
    BigDecimal total_amount,
    int total_count,
    String memo,
    String expire_at,
    BigDecimal balance_after,
    String created_at
) {}
