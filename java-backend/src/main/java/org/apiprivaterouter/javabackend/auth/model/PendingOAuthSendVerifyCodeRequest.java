package org.apiprivaterouter.javabackend.auth.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PendingOAuthSendVerifyCodeRequest(
        @NotBlank @Email String email,
        @JsonProperty("turnstile_token") String turnstileToken,
        @JsonProperty("pending_auth_token") String pendingAuthToken,
        @JsonProperty("pending_oauth_token") String pendingOauthToken
) {
}
