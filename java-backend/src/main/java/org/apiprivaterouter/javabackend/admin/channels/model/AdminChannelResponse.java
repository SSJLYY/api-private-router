package org.apiprivaterouter.javabackend.admin.channels.model;

import java.util.List;
import java.util.Map;

public record AdminChannelResponse(
        long id,
        String name,
        String description,
        String status,
        String billing_model_source,
        boolean restrict_models,
        Map<String, Object> features_config,
        List<Long> group_ids,
        List<ChannelModelPricingResponse> model_pricing,
        Map<String, Map<String, String>> model_mapping,
        boolean apply_pricing_to_account_stats,
        List<AccountStatsPricingRuleResponse> account_stats_pricing_rules,
        String created_at,
        String updated_at
) {
}
