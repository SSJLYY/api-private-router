package org.apiprivaterouter.javabackend.admin.account.service;

import org.apiprivaterouter.javabackend.admin.account.model.CreateScheduledTestPlanRequest;
import org.apiprivaterouter.javabackend.admin.account.model.ScheduledTestPlanResponse;
import org.apiprivaterouter.javabackend.admin.account.model.ScheduledTestResultResponse;
import org.apiprivaterouter.javabackend.admin.account.model.UpdateScheduledTestPlanRequest;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ScheduledTestPlanService {

    private static final int DEFAULT_MAX_RESULTS = 50;

    private final AdminAccountService accountService;
    private final AdminAccountRepository repository;
    private final ScheduledTestCronSupport cronSupport;

    public ScheduledTestPlanService(
            AdminAccountService accountService,
            AdminAccountRepository repository,
            ScheduledTestCronSupport cronSupport
    ) {
        this.accountService = accountService;
        this.repository = repository;
        this.cronSupport = cronSupport;
    }

    public List<ScheduledTestPlanResponse> listByAccount(long accountId) {
        accountService.getAccount(accountId);
        return repository.listScheduledTestPlansByAccount(accountId);
    }

    public ScheduledTestPlanResponse getPlan(long id) {
        return repository.getScheduledTestPlan(id)
                .orElseThrow(() -> new IllegalArgumentException("scheduled test plan not found"));
    }

    public ScheduledTestPlanResponse createPlan(CreateScheduledTestPlanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        long accountId = request.account_id() == null ? 0L : request.account_id();
        if (accountId <= 0) {
            throw new IllegalArgumentException("account_id is required");
        }
        accountService.getAccount(accountId);
        String cronExpression = requireCron(request.cron_expression());
        Instant nextRunAt = cronSupport.computeNextRun(cronExpression, Instant.now());
        return repository.createScheduledTestPlan(
                accountId,
                normalizeModelId(request.model_id()),
                cronExpression,
                request.enabled() == null || request.enabled(),
                normalizeMaxResultsForCreate(request.max_results()),
                request.auto_recover() != null && request.auto_recover(),
                nextRunAt
        );
    }

    public ScheduledTestPlanResponse updatePlan(long id, UpdateScheduledTestPlanRequest request) {
        ScheduledTestPlanResponse current = getPlan(id);
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }

        String modelId = current.model_id();
        if (request.model_id() != null && !request.model_id().trim().isEmpty()) {
            modelId = request.model_id().trim();
        }

        String cronExpression = current.cron_expression();
        if (request.cron_expression() != null && !request.cron_expression().trim().isEmpty()) {
            cronExpression = request.cron_expression().trim();
        }
        cronExpression = requireCron(cronExpression);

        boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
        int maxResults = current.max_results();
        if (request.max_results() != null && request.max_results() > 0) {
            maxResults = request.max_results();
        }
        boolean autoRecover = request.auto_recover() == null ? current.auto_recover() : request.auto_recover();

        Instant nextRunAt = cronSupport.computeNextRun(cronExpression, Instant.now());
        return repository.updateScheduledTestPlan(
                id,
                modelId,
                cronExpression,
                enabled,
                maxResults,
                autoRecover,
                nextRunAt
        );
    }

    public void deletePlan(long id) {
        getPlan(id);
        repository.deleteScheduledTestPlan(id);
    }

    public List<ScheduledTestResultResponse> listResults(long planId, Integer limit) {
        getPlan(planId);
        int normalizedLimit = limit == null || limit <= 0 ? DEFAULT_MAX_RESULTS : limit;
        return repository.listScheduledTestResults(planId, normalizedLimit);
    }

    public void saveResult(long planId, int maxResults, AdminAccountTestService.BackgroundTestResult result) {
        repository.createScheduledTestResult(
                planId,
                result.status(),
                result.responseText(),
                result.errorMessage(),
                result.latencyMs(),
                result.startedAt(),
                result.finishedAt()
        );
        repository.pruneScheduledTestResults(planId, maxResults <= 0 ? DEFAULT_MAX_RESULTS : maxResults);
    }

    private String requireCron(String cronExpression) {
        if (cronExpression == null || cronExpression.trim().isEmpty()) {
            throw new IllegalArgumentException("cron_expression is required");
        }
        return cronExpression.trim();
    }

    private String normalizeModelId(String modelId) {
        return modelId == null ? "" : modelId.trim();
    }

    private int normalizeMaxResultsForCreate(Integer maxResults) {
        return maxResults == null || maxResults <= 0 ? DEFAULT_MAX_RESULTS : maxResults;
    }
}
