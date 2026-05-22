package org.apiprivaterouter.javabackend.admin.account.model;

public record MixedChannelWarningDetailsResponse(
        long group_id,
        String group_name,
        String current_platform,
        String other_platform
) {
}
