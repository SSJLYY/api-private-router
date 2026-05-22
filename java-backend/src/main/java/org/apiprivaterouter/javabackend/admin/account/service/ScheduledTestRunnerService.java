package org.apiprivaterouter.javabackend.admin.account.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apiprivaterouter.javabackend.admin.account.model.ScheduledTestPlanResponse;
import org.apiprivaterouter.javabackend.admin.account.repository.AdminAccountRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Service
public class ScheduledTestRunnerService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTestRunnerService.class);
    private static final int MAX_WORKERS = 10;
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "api-private-router-scheduled-test-runner");
        thread.setDaemon(true);
        return thread;
    });

    private final ScheduledTestPlanService scheduledTestPlanService;
    private final ScheduledTestCronSupport cronSupport;
    private final AdminAccountRepository repository;
    private final AdminAccountTestService accountTestService;
    private final Semaphore semaphore = new Semaphore(MAX_WORKERS);

    public ScheduledTestRunnerService(
            ScheduledTestPlanService scheduledTestPlanService,
            ScheduledTestCronSupport cronSupport,
            AdminAccountRepository repository,
            AdminAccountTestService accountTestService
    ) {
        this.scheduledTestPlanService = scheduledTestPlanService;
        this.cronSupport = cronSupport;
        this.repository = repository;
        this.accountTestService = accountTestService;
    }

    @Scheduled(cron = "0 * * * * *")
    public void scheduleTick() {
        EXECUTOR.execute(this::runScheduledOnce);
    }

    private void runScheduledOnce() {
        try {
            Thread.sleep(10_000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }

        Instant now = Instant.now();
        List<ScheduledTestPlanResponse> plans = repository.listDueScheduledTestPlans(now);
        if (plans.isEmpty()) {
            return;
        }
        log.info("scheduled-test runner found {} due plans", plans.size());

        for (ScheduledTestPlanResponse plan : plans) {
            try {
                semaphore.acquire();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            new Thread(() -> {
                try {
                    runOnePlan(plan);
                } finally {
                    semaphore.release();
                }
            }, "scheduled-test-plan-" + plan.id()).start();
        }
    }

    private void runOnePlan(ScheduledTestPlanResponse plan) {
        Instant startedAt = Instant.now();
        try {
            AdminAccountTestService.BackgroundTestResult result =
                    accountTestService.runTestBackground(plan.account_id(), plan.model_id());
            scheduledTestPlanService.saveResult(plan.id(), plan.max_results(), result);
            if ("success".equals(result.status()) && plan.auto_recover()) {
                accountTestService.recoverAfterSuccessfulTest(plan.account_id());
            }
            Instant nextRunAt = cronSupport.computeNextRun(plan.cron_expression(), Instant.now());
            repository.updateScheduledTestPlanAfterRun(plan.id(), startedAt, nextRunAt);
        } catch (Exception ex) {
            log.warn("scheduled-test runner failed for plan {}", plan.id(), ex);
        }
    }
}
