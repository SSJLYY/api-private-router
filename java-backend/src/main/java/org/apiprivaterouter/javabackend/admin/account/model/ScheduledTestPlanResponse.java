package org.apiprivaterouter.javabackend.admin.account.model;

public record ScheduledTestPlanResponse(
        long id,
        long account_id,
        String model_id,
        String cron_expression,
        boolean enabled,
        int max_results,
        boolean auto_recover,
        String last_run_at,
        String next_run_at,
        String created_at,
        String updated_at
) {
}
