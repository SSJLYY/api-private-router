package org.apiprivaterouter.javabackend.admin.affiliate.model;

import java.time.OffsetDateTime;

public record AffiliateRebateRecord(
        long order_id,
        String out_trade_no,
        long inviter_id,
        String inviter_email,
        String inviter_username,
        long invitee_id,
        String invitee_email,
        String invitee_username,
        double order_amount,
        double pay_amount,
        double rebate_amount,
        String payment_type,
        String order_status,
        OffsetDateTime created_at
) {
}
