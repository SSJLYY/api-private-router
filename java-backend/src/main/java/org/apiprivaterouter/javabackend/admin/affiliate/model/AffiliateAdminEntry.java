package org.apiprivaterouter.javabackend.admin.affiliate.model;

public record AffiliateAdminEntry(
        long user_id,
        String email,
        String username,
        String aff_code,
        boolean aff_code_custom,
        Double aff_rebate_rate_percent,
        int aff_count
) {
}
