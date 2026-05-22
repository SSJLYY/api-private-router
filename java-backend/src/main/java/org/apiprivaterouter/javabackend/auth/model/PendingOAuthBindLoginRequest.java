package org.apiprivaterouter.javabackend.auth.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PendingOAuthBindLoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @JsonProperty("adopt_display_name") Boolean adoptDisplayName,
        @JsonProperty("adopt_avatar") Boolean adoptAvatar
) {
    public OAuthAdoptionDecisionRequest adoptionDecision() {
        return new OAuthAdoptionDecisionRequest(adoptDisplayName, adoptAvatar);
    }
}
