package org.apiprivaterouter.javabackend.admin.subscription.model;

public record ResetSubscriptionQuotaRequest(
        boolean daily,
        boolean weekly,
        boolean monthly
) {
}
