package org.apiprivaterouter.javabackend.userredpacket.model;

import java.math.BigDecimal;

public record RedpacketResponse(
    long id,
    long creator_id,
    String code,
    String redpacket_type,
    BigDecimal total_amount,
    BigDecimal remaining_amount,
    int total_count,
    int remaining_count,
    String memo,
    String expire_at,
    String status,
    String created_at
) {}
