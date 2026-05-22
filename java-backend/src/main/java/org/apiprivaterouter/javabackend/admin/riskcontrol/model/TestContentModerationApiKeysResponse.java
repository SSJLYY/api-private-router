package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

import java.util.List;

public record TestContentModerationApiKeysResponse(
        List<ContentModerationApiKeyStatus> items,
        ContentModerationTestAuditResult audit_result,
        int image_count
) {
}
