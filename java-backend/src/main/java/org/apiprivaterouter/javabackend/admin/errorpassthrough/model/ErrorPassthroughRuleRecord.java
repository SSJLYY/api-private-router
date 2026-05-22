package org.apiprivaterouter.javabackend.admin.errorpassthrough.model;

import java.time.OffsetDateTime;
import java.util.List;

public record ErrorPassthroughRuleRecord(
        long id,
        String name,
        boolean enabled,
        int priority,
        List<Integer> errorCodes,
        List<String> keywords,
        String matchMode,
        List<String> platforms,
        boolean passthroughCode,
        Integer responseCode,
        boolean passthroughBody,
        String customMessage,
        boolean skipMonitoring,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
