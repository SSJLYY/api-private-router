package org.apiprivaterouter.javabackend.admin.apikey.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apiprivaterouter.javabackend.userkeys.model.UserApiKeyResponse;

public record AdminUpdateApiKeyGroupResponse(
        @JsonProperty("api_key")
        UserApiKeyResponse apiKey,
        @JsonProperty("auto_granted_group_access")
        boolean autoGrantedGroupAccess,
        @JsonProperty("granted_group_id")
        Long grantedGroupId,
        @JsonProperty("granted_group_name")
        String grantedGroupName
) {
}
