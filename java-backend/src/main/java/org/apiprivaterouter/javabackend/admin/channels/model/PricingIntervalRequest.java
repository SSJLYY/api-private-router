package org.apiprivaterouter.javabackend.admin.channels.model;

public record PricingIntervalRequest(
        Long id,
        int min_tokens,
        Integer max_tokens,
        String tier_label,
        Double input_price,
        Double output_price,
        Double cache_write_price,
        Double cache_read_price,
        Double per_request_price,
        int sort_order
) {
}
