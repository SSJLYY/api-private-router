package org.apiprivaterouter.javabackend.availablechannels.model;

public record UserPricingIntervalResponse(
        int min_tokens,
        Integer max_tokens,
        String tier_label,
        Double input_price,
        Double output_price,
        Double cache_write_price,
        Double cache_read_price,
        Double per_request_price
) {
}
