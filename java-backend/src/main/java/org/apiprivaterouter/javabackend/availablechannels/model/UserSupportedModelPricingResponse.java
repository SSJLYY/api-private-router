package org.apiprivaterouter.javabackend.availablechannels.model;

import java.util.List;

public record UserSupportedModelPricingResponse(
        String billing_mode,
        Double input_price,
        Double output_price,
        Double cache_write_price,
        Double cache_read_price,
        Double image_output_price,
        Double per_request_price,
        List<UserPricingIntervalResponse> intervals
) {
}
