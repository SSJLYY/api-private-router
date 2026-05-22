package org.apiprivaterouter.javabackend.userkeys.model;

import org.apiprivaterouter.javabackend.usergroups.model.UserAvailableGroupResponse;

public record UserApiKeyResponse(
        long id,
        long user_id,
        String key,
        String name,
        Long group_id,
        String status,
        java.util.List<String> ip_whitelist,
        java.util.List<String> ip_blacklist,
        String last_used_at,
        double quota,
        double quota_used,
        String expires_at,
        String created_at,
        String updated_at,
        double rate_limit_5h,
        double rate_limit_1d,
        double rate_limit_7d,
        double usage_5h,
        double usage_1d,
        double usage_7d,
        String window_5h_start,
        String window_1d_start,
        String window_7d_start,
        String reset_5h_at,
        String reset_1d_at,
        String reset_7d_at,
        UserAvailableGroupResponse group
) {
}
