package org.apiprivaterouter.javabackend.admin.openai.model;

public record OpenAiOAuthTokenResponse(
        String access_token,
        String refresh_token,
        String id_token,
        String token_type,
        long expires_in,
        long expires_at,
        String scope,
        String client_id,
        String email,
        String chatgpt_account_id,
        String chatgpt_user_id,
        String organization_id,
        String plan_type,
        String subscription_expires_at,
        String privacy_mode
) {
}
