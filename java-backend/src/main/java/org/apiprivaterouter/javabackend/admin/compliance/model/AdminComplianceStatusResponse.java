package org.apiprivaterouter.javabackend.admin.compliance.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdminComplianceStatusResponse(
        boolean required,
        String version,
        Map<String, String> document_urls,
        Map<String, String> ack_phrases,
        AdminComplianceAcknowledgement acknowledgement
) {
}
