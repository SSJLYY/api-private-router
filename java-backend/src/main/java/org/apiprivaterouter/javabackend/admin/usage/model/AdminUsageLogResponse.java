package org.apiprivaterouter.javabackend.admin.usage.model;

public record AdminUsageLogResponse(
        long id,
        long user_id,
        long api_key_id,
        Long account_id,
        String request_id,
        String model,
        String upstream_model,
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
        Double account_rate_multiplier,
        Double account_stats_cost,
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
        Long channel_id,
        String model_mapping_chain,
        String billing_tier,
        String ip_address,
        String created_at,
        UserSummary user,
        ApiKeySummary api_key,
        GroupSummary group,
        AccountSummary account
) {
    public record UserSummary(long id, String email, String username) {
    }

    public record ApiKeySummary(long id, String name, long user_id) {
    }

    public record GroupSummary(long id, String name) {
    }

    public record AccountSummary(long id, String name) {
    }
}
