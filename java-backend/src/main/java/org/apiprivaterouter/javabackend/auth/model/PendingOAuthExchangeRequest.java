package org.apiprivaterouter.javabackend.auth.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PendingOAuthExchangeRequest(
        @JsonProperty("adopt_display_name") Boolean adoptDisplayName,
        @JsonProperty("adopt_avatar") Boolean adoptAvatar
) {
    public OAuthAdoptionDecisionRequest adoptionDecision() {
        return new OAuthAdoptionDecisionRequest(adoptDisplayName, adoptAvatar);
    }
}
