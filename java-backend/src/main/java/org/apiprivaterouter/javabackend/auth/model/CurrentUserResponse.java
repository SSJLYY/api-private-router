package org.apiprivaterouter.javabackend.auth.model;

import org.apiprivaterouter.javabackend.usercenter.model.NotifyEmailEntry;

import java.util.List;
import java.util.Map;

public record CurrentUserResponse(
        long id,
        String username,
        String email,
        String avatar_url,
        ProfileSourceContext avatar_source,
        ProfileSourceContext username_source,
        ProfileSourceContext display_name_source,
        ProfileSourceContext nickname_source,
        Map<String, ProfileSourceContext> profile_sources,
        Map<String, AuthBindingStatus> auth_bindings,
        Map<String, AuthBindingStatus> identity_bindings,
        boolean email_bound,
        boolean linuxdo_bound,
        boolean oidc_bound,
        boolean wechat_bound,
        String role,
        double balance,
        int concurrency,
        String status,
        List<Long> allowed_groups,
        boolean balance_notify_enabled,
        Double balance_notify_threshold,
        List<NotifyEmailEntry> balance_notify_extra_emails,
        Integer rpm_limit,
        String signup_source,
        String last_active_at,
        String created_at,
        String updated_at,
        String run_mode
) {
    public record ProfileSourceContext(
            String provider,
            String source,
            String label,
            String provider_label
    ) {
    }

    public record AuthBindingStatus(
            boolean bound,
            Integer bound_count,
            String provider,
            String provider_key,
            String provider_subject,
            String issuer,
            String label,
            String provider_label,
            String display_name,
            String subject_hint,
            String verified_at,
            String bind_start_path,
            Boolean can_bind,
            Boolean can_unbind,
            String note_key,
            String note,
            Map<String, Object> metadata
    ) {
    }
}
