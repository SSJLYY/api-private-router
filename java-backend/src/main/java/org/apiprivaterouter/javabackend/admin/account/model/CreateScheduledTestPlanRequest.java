package org.apiprivaterouter.javabackend.admin.account.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateScheduledTestPlanRequest(
        @NotNull Long account_id,
        String model_id,
        @NotBlank String cron_expression,
        Boolean enabled,
        Integer max_results,
        Boolean auto_recover
) {
}
