package org.apiprivaterouter.javabackend.usercenter.model;

import java.util.List;

public record UpdateProfileRequest(
        String username,
        String avatar_url,
        Boolean balance_notify_enabled,
        Double balance_notify_threshold,
        List<NotifyEmailEntry> balance_notify_extra_emails
) {
}
