package org.apiprivaterouter.javabackend.userredeem.model;

import com.fasterxml.jackson.annotation.JsonInclude;

public record UserRedeemHistoryItemResponse(
        long id,
        String code,
        String type,
        double value,
        String status,
        Long used_by,
        String used_at,
        String created_at,
        Long group_id,
        int validity_days,
        @JsonInclude(JsonInclude.Include.NON_NULL) String notes,
        @JsonInclude(JsonInclude.Include.NON_NULL) GroupSummary group
) {
    public record GroupSummary(
            long id,
            String name
    ) {
    }
}
