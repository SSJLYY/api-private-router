package org.apiprivaterouter.javabackend.riskcontrol.runtime.model;

import java.util.Map;

public record ContentModerationDecision(
        boolean allowed,
        boolean blocked,
        boolean flagged,
        String message,
        int statusCode,
        String inputHash,
        String highestCategory,
        double highestScore,
        Map<String, Double> categoryScores,
        String action
) {

    public static ContentModerationDecision allow() {
        return new ContentModerationDecision(
                true,
                false,
                false,
                "",
                0,
                "",
                "",
                0D,
                Map.of(),
                "allow"
        );
    }
}
