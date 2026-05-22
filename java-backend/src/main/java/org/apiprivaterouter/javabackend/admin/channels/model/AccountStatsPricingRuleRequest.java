package org.apiprivaterouter.javabackend.admin.channels.model;

import java.util.List;

public record AccountStatsPricingRuleRequest(
        Long id,
        String name,
        List<Long> group_ids,
        List<Long> account_ids,
        List<ChannelModelPricingRequest> pricing
) {
}
