package org.apiprivaterouter.javabackend.admin.group.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record GroupModelsListConfig(
        boolean enabled,
        List<String> models
) {
    public boolean customModelsListEnabled() {
        return enabled && models != null && !models.isEmpty();
    }
}
