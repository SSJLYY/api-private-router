package org.apiprivaterouter.javabackend.admin.affiliate.model;

public record UpdateAffiliateUserRequest(
        String aff_code,
        Double aff_rebate_rate_percent,
        Boolean clear_rebate_rate
) {
}
