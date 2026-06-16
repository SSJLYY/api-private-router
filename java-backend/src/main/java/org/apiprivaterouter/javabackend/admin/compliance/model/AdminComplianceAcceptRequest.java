package org.apiprivaterouter.javabackend.admin.compliance.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdminComplianceAcceptRequest(
        String phrase,
        String language
) {
}
