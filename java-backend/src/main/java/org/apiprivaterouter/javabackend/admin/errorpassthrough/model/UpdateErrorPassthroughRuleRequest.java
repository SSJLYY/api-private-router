package org.apiprivaterouter.javabackend.admin.errorpassthrough.model;

import java.util.List;

public record UpdateErrorPassthroughRuleRequest(
        String name,
        Boolean enabled,
        Integer priority,
        List<Integer> error_codes,
        List<String> keywords,
        String match_mode,
        List<String> platforms,
        Boolean passthrough_code,
        Integer response_code,
        Boolean passthrough_body,
        String custom_message,
        Boolean skip_monitoring,
        String description
) {
}
