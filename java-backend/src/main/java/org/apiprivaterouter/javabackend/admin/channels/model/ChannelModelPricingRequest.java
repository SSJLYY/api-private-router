package org.apiprivaterouter.javabackend.admin.channels.model;

import java.util.List;

public record ChannelModelPricingRequest(
        Long id,
        String platform,
        List<String> models,
        String billing_mode,
        Double input_price,
        Double output_price,
        Double cache_write_price,
        Double cache_read_price,
        Double image_output_price,
        Double per_request_price,
        List<PricingIntervalRequest> intervals
) {
}
