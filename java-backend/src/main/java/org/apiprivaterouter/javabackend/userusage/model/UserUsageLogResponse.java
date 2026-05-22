package org.apiprivaterouter.javabackend.userusage.model;

public record UserUsageLogResponse(
        long id,
        long user_id,
        long api_key_id,
        Long account_id,
        String request_id,
        String model,
        String service_tier,
        String reasoning_effort,
        String inbound_endpoint,
        String upstream_endpoint,
        Long group_id,
        Long subscription_id,
        int input_tokens,
        int output_tokens,
        int cache_creation_tokens,
        int cache_read_tokens,
        int cache_creation_5m_tokens,
        int cache_creation_1h_tokens,
        double input_cost,
        double output_cost,
        double cache_creation_cost,
        double cache_read_cost,
        double total_cost,
        double actual_cost,
        double rate_multiplier,
        int billing_type,
        String request_type,
        boolean stream,
        boolean openai_ws_mode,
        Integer duration_ms,
        Integer first_token_ms,
        int image_count,
        String image_size,
        String user_agent,
        boolean cache_ttl_overridden,
        String billing_mode,
        String created_at,
        UserSummary user,
        ApiKeySummary api_key,
        GroupSummary group
) {
    public record UserSummary(long id, String email, String username) {
    }

    public record ApiKeySummary(long id, String name, long user_id) {
    }

    public record GroupSummary(long id, String name) {
    }
}
