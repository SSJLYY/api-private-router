package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

import java.util.Map;

public record ContentModerationTestAuditResult(
        boolean flagged,
        String highest_category,
        double highest_score,
        double composite_score,
        Map<String, Double> category_scores,
        Map<String, Double> thresholds
) {
}
