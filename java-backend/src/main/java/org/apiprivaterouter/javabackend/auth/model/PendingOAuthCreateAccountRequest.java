package org.apiprivaterouter.javabackend.auth.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PendingOAuthCreateAccountRequest(
        @NotBlank @Email String email,
        @JsonProperty("verify_code") String verifyCode,
        @NotBlank @Size(min = 6) String password,
        @JsonProperty("invitation_code") String invitationCode,
        @JsonProperty("aff_code") String affiliateCode,
        @JsonProperty("adopt_display_name") Boolean adoptDisplayName,
        @JsonProperty("adopt_avatar") Boolean adoptAvatar
) {
    public OAuthAdoptionDecisionRequest adoptionDecision() {
        return new OAuthAdoptionDecisionRequest(adoptDisplayName, adoptAvatar);
    }
}
