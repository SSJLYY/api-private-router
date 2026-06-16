package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

import java.util.List;

public record ContentModerationModelFilterResponse(
        String mode,
        List<String> models
) {
}
