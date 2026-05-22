package org.apiprivaterouter.javabackend.admin.account.model;

public record SimpleGroupResponse(
        long id,
        String name,
        String description,
        String platform,
        double rate_multiplier,
        int rpm_limit,
        boolean is_exclusive,
        String status,
        String subscription_type,
        Double daily_limit_usd,
        Double weekly_limit_usd,
        Double monthly_limit_usd,
        boolean allow_image_generation,
        boolean image_rate_independent,
        double image_rate_multiplier,
        Double image_price_1k,
        Double image_price_2k,
        Double image_price_4k,
        boolean claude_code_only,
        Long fallback_group_id,
        Long fallback_group_id_on_invalid_request,
        boolean allow_messages_dispatch,
        String default_mapped_model,
        boolean require_oauth_only,
        boolean require_privacy_set,
        String created_at,
        String updated_at
) {
}
