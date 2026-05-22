package org.apiprivaterouter.javabackend.admin.datamanagement.model;

public record DataManagementAgentInfoResponse(
        String status,
        String version,
        long uptime_seconds
) {
}
