package org.apiprivaterouter.javabackend.userredpacket.model;

import java.math.BigDecimal;
import java.util.List;

public record RedpacketDetailResponse(
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
    String created_at,
    List<ClaimItem> claims
) {
    public record ClaimItem(
        long id,
        long user_id,
        String user_email,
        BigDecimal amount,
        boolean is_best_luck,
        String created_at
    ) {}
}
