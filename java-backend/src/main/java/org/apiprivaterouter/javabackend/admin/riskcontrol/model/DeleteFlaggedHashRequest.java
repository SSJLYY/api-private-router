package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeleteFlaggedHashRequest(
        String input_hash
) {
}
