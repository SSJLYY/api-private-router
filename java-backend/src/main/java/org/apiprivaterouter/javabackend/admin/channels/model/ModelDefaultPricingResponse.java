package org.apiprivaterouter.javabackend.admin.channels.model;

public record ModelDefaultPricingResponse(
        boolean found,
        Double input_price,
        Double output_price,
        Double cache_write_price,
        Double cache_read_price,
        Double image_output_price
) {
}
