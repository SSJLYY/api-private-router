package org.apiprivaterouter.javabackend.auth.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OAuthAdoptionDecisionRequest(
        @JsonProperty("adopt_display_name") Boolean adoptDisplayName,
        @JsonProperty("adopt_avatar") Boolean adoptAvatar
) {
    public boolean hasDecision() {
        return adoptDisplayName != null || adoptAvatar != null;
    }

    public boolean resolvedAdoptDisplayName() {
        return Boolean.TRUE.equals(adoptDisplayName);
    }

    public boolean resolvedAdoptAvatar() {
        return Boolean.TRUE.equals(adoptAvatar);
    }
}
