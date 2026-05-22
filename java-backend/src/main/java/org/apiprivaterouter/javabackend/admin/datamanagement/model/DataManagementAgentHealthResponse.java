package org.apiprivaterouter.javabackend.admin.datamanagement.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DataManagementAgentHealthResponse(
        boolean enabled,
        String reason,
        String socket_path,
        DataManagementAgentInfoResponse agent
) {
}
