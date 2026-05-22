package org.apiprivaterouter.javabackend.usercenter.model;

import java.util.List;

public record UserProfileResponse(
        long id,
        String email,
        String username,
        String avatar_url,
        String role,
        String status,
        double balance,
        int concurrency,
        Integer rpm_limit,
        java.util.List<Long> allowed_groups,
        boolean balance_notify_enabled,
        Double balance_notify_threshold,
        List<NotifyEmailEntry> balance_notify_extra_emails,
        List<String> auth_providers,
        String signup_source,
        String last_active_at,
        String created_at,
        String updated_at
) {
}
