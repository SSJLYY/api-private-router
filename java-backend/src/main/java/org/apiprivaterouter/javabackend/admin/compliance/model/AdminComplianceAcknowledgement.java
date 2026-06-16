package org.apiprivaterouter.javabackend.admin.compliance.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdminComplianceAcknowledgement(
        String version,
        long admin_user_id,
        String ip_address,
        String user_agent,
        String accepted_at
) {
}
