package org.apiprivaterouter.javabackend.admin.channels.model;

import java.util.List;

public record AccountStatsPricingRuleResponse(
        Long id,
        String name,
        List<Long> group_ids,
        List<Long> account_ids,
        List<ChannelModelPricingResponse> pricing
) {
}
