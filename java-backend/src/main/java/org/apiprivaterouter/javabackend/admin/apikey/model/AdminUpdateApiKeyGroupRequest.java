package org.apiprivaterouter.javabackend.admin.apikey.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AdminUpdateApiKeyGroupRequest(
        @JsonProperty("group_id")
        Long groupId,
        @JsonProperty("reset_rate_limit_usage")
        Boolean resetRateLimitUsage
) {
}
