package org.apiprivaterouter.javabackend.admin.channels.model;

import java.util.List;
import java.util.Map;

public record CreateAdminChannelRequest(
        String name,
        String description,
        List<Long> group_ids,
        List<ChannelModelPricingRequest> model_pricing,
        Map<String, Map<String, String>> model_mapping,
        String billing_model_source,
        Boolean restrict_models,
        Map<String, Object> features_config,
        Boolean apply_pricing_to_account_stats,
        List<AccountStatsPricingRuleRequest> account_stats_pricing_rules
) {
}
