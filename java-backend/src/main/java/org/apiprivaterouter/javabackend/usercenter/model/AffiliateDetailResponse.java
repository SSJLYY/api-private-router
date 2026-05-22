package org.apiprivaterouter.javabackend.usercenter.model;

public record AffiliateDetailResponse(
        String invite_code,
        double available_quota,
        double transferred_quota,
        int invited_users
) {
}
