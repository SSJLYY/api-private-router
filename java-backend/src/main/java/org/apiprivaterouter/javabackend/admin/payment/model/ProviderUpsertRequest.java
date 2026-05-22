package org.apiprivaterouter.javabackend.admin.payment.model;

import java.util.List;
import java.util.Map;

public record ProviderUpsertRequest(
        String provider_key,
        String name,
        Map<String, String> config,
        List<String> supported_types,
        Boolean enabled,
        String payment_mode,
        Integer sort_order,
        String limits,
        Boolean refund_enabled,
        Boolean allow_user_refund
) {
}
