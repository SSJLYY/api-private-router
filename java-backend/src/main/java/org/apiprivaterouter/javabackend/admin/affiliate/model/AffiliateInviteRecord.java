package org.apiprivaterouter.javabackend.admin.affiliate.model;

import java.time.OffsetDateTime;

public record AffiliateInviteRecord(
        long inviter_id,
        String inviter_email,
        String inviter_username,
        long invitee_id,
        String invitee_email,
        String invitee_username,
        String aff_code,
        double total_rebate,
        OffsetDateTime created_at
) {
}
