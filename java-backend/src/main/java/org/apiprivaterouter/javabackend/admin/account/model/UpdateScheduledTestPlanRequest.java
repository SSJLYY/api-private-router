package org.apiprivaterouter.javabackend.admin.account.model;

public record UpdateScheduledTestPlanRequest(
        String model_id,
        String cron_expression,
        Boolean enabled,
        Integer max_results,
        Boolean auto_recover
) {
}
