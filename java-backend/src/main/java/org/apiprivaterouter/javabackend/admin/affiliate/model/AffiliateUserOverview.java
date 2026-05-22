package org.apiprivaterouter.javabackend.admin.affiliate.model;

public record AffiliateUserOverview(
        long user_id,
        String email,
        String username,
        String aff_code,
        double rebate_rate_percent,
        int invited_count,
        int rebated_invitee_count,
        double available_quota,
        double history_quota
) {
}
