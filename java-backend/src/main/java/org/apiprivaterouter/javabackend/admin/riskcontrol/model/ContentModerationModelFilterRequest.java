package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentModerationModelFilterRequest(
        String mode,
        List<String> models
) {
}
