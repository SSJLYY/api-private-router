package org.apiprivaterouter.javabackend.admin.errorpassthrough.model;

import java.util.List;

public record ErrorPassthroughRuleResponse(
        long id,
        String name,
        boolean enabled,
        int priority,
        List<Integer> error_codes,
        List<String> keywords,
        String match_mode,
        List<String> platforms,
        boolean passthrough_code,
        Integer response_code,
        boolean passthrough_body,
        String custom_message,
        boolean skip_monitoring,
        String description,
        String created_at,
        String updated_at
) {
}
