package org.apiprivaterouter.javabackend.admin.subscription.model;

import java.util.List;
import java.util.Map;

public record BulkAssignSubscriptionResponse(
        int success_count,
        int created_count,
        int reused_count,
        int failed_count,
        List<AdminSubscriptionResponse> subscriptions,
        List<String> errors,
        Map<String, String> statuses
) {
}
