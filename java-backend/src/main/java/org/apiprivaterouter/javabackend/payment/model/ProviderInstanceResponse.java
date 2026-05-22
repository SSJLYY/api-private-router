package org.apiprivaterouter.javabackend.payment.model;

import java.util.List;
import java.util.Map;

public record ProviderInstanceResponse(
        long id,
        String provider_key,
        String name,
        Map<String, String> config,
        List<String> supported_types,
        boolean enabled,
        String payment_mode,
        boolean refund_enabled,
        boolean allow_user_refund,
        String limits,
        int sort_order
) {
}
