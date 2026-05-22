package org.apiprivaterouter.javabackend.admin.model;

public record AdminUserResponse(
        long id,
        String email,
        String username,
        String role,
        String status,
        double balance,
        int concurrency,
        Integer rpm_limit,
        int current_concurrency,
        java.util.List<Long> allowed_groups,
        boolean balance_notify_enabled,
        Double balance_notify_threshold,
        java.util.List<org.apiprivaterouter.javabackend.usercenter.model.NotifyEmailEntry> balance_notify_extra_emails,
        String notes,
        String last_active_at,
        String last_used_at,
        String created_at,
        String updated_at
) {
}
