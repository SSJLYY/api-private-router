package org.apiprivaterouter.javabackend.availablechannels.model;

public record UserSupportedModelResponse(
        String name,
        String platform,
        UserSupportedModelPricingResponse pricing
) {
}
