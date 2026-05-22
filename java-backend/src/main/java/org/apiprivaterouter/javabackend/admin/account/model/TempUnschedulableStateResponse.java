package org.apiprivaterouter.javabackend.admin.account.model;

public record TempUnschedulableStateResponse(
        long until_unix,
        Long triggered_at_unix,
        Integer status_code,
        String matched_keyword,
        int rule_index,
        String error_message
) {
}
