package org.apiprivaterouter.javabackend.admin.ops.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.apiprivaterouter.javabackend.admin.ops.repository.AdminOpsRepository;
import org.apiprivaterouter.javabackend.admin.ops.service.AdminOpsRetryExecutor.RetryExecutionResult;
import org.apiprivaterouter.javabackend.common.api.PageResponse;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminOpsServiceTest {

    @Test
    void listRequestDetailsRejectsInvalidKind() {
        AdminOpsRepository repository = mock(AdminOpsRepository.class);
        when(repository.isMonitoringEnabled()).thenReturn(true);
        AdminOpsService service = new AdminOpsService(repository, mock(AdminOpsRetryExecutor.class));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.listRequestDetails(Map.of("kind", "weird"), 1, 20));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void listRequestDetailsRejectsInvalidUserId() {
        AdminOpsRepository repository = mock(AdminOpsRepository.class);
        when(repository.isMonitoringEnabled()).thenReturn(true);
        AdminOpsService service = new AdminOpsService(repository, mock(AdminOpsRetryExecutor.class));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.listRequestDetails(Map.of("user_id", "0"), 1, 20));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void listRequestDetailsRequiresMonitoringEnabled() {
        AdminOpsRepository repository = mock(AdminOpsRepository.class);
        when(repository.isMonitoringEnabled()).thenReturn(false);
        AdminOpsService service = new AdminOpsService(repository, mock(AdminOpsRetryExecutor.class));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.listRequestDetails(Map.of(), 1, 20));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
    }

    @Test
    void listRequestDetailsNormalizesAndDelegates() {
        AdminOpsRepository repository = mock(AdminOpsRepository.class);
        when(repository.isMonitoringEnabled()).thenReturn(true);
        when(repository.listRequestDetails(any(Map.class), eq(2), eq(100)))
                .thenReturn(new PageResponse<>(List.of(), 0L, 2, 100));
        AdminOpsService service = new AdminOpsService(repository, mock(AdminOpsRetryExecutor.class));

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("kind", "ALL");
        filter.put("sort", "duration_desc");
        filter.put("user_id", "12");
        filter.put("min_duration_ms", "5");

        service.listRequestDetails(filter, 2, 999);

        verify(repository).listRequestDetails(any(Map.class), eq(2), eq(100));
    }

    @Test
    void retryClientErrorReturnsSucceededPayloadAndMarksResolved() {
        AdminOpsRepository repository = mock(AdminOpsRepository.class);
        AdminOpsRetryExecutor retryExecutor = mock(AdminOpsRetryExecutor.class);
        when(repository.isMonitoringEnabled()).thenReturn(true);
        when(repository.getErrorLog(12L)).thenReturn(Map.of(
                "id", 12L,
                "request_body", "{\"model\":\"gpt-5\"}",
                "group_id", 5L
        ));
        when(repository.findLatestRetryAttempt(12L)).thenReturn(java.util.Optional.empty());
        when(repository.insertRetryAttemptRunning(eq(12L), eq(9L), eq("client"), isNull(), any(Instant.class)))
                .thenReturn(Map.of("id", 77L));
        when(retryExecutor.executeClientRetry(any(Map.class)))
                .thenReturn(new RetryExecutionResult(true, false, 1001L, 200, "req_123", "{\"ok\":true}", false, ""));
        AdminOpsService service = new AdminOpsService(repository, retryExecutor);

        Map<String, Object> payload = service.retryClientError(12L, 9L);

        assertEquals("client", payload.get("mode"));
        assertEquals("succeeded", payload.get("status"));
        assertEquals(1001L, payload.get("used_account_id"));
        assertEquals(200, payload.get("http_status_code"));
        assertEquals("req_123", payload.get("upstream_request_id"));
        verify(repository).markErrorResolvedByRetry(eq(12L), eq(9L), eq(77L), any(Instant.class));
    }

    @Test
    void retryUpstreamEventUsesEventAccountAndMode() {
        AdminOpsRepository repository = mock(AdminOpsRepository.class);
        AdminOpsRetryExecutor retryExecutor = mock(AdminOpsRetryExecutor.class);
        when(repository.isMonitoringEnabled()).thenReturn(true);
        when(repository.getErrorLog(44L)).thenReturn(Map.of(
                "id", 44L,
                "request_body", "{\"model\":\"gpt-5\"}",
                "group_id", 5L,
                "upstream_errors", "[{\"account_id\":321,\"upstream_request_body\":\"{\\\"model\\\":\\\"gpt-5\\\"}\"}]"
        ));
        when(repository.findLatestRetryAttempt(44L)).thenReturn(java.util.Optional.empty());
        when(repository.insertRetryAttemptRunning(eq(44L), eq(9L), eq("upstream_event"), eq(321L), any(Instant.class)))
                .thenReturn(Map.of("id", 88L));
        when(retryExecutor.executePinnedRetry(any(Map.class), eq(321L)))
                .thenReturn(new RetryExecutionResult(false, false, 321L, 502, "", "", false, "boom"));
        AdminOpsService service = new AdminOpsService(repository, retryExecutor);

        Map<String, Object> payload = service.retryUpstreamEvent(44L, 0, 9L);

        assertEquals("upstream_event", payload.get("mode"));
        assertEquals(321L, payload.get("pinned_account_id"));
        assertEquals("failed", payload.get("status"));
        verify(repository, never()).markErrorResolvedByRetry(any(Long.class), any(Long.class), any(Long.class), any(Instant.class));
    }

    @Test
    void retryErrorRejectsRecentAttempt() {
        AdminOpsRepository repository = mock(AdminOpsRepository.class);
        AdminOpsRetryExecutor retryExecutor = mock(AdminOpsRetryExecutor.class);
        when(repository.isMonitoringEnabled()).thenReturn(true);
        when(repository.getErrorLog(12L)).thenReturn(Map.of(
                "id", 12L,
                "request_body", "{\"model\":\"gpt-5\"}",
                "group_id", 5L
        ));
        when(repository.findLatestRetryAttempt(12L)).thenReturn(java.util.Optional.of(Map.of(
                "status", "failed",
                "finished_at", Instant.now().toString()
        )));
        AdminOpsService service = new AdminOpsService(repository, retryExecutor);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.retryClientError(12L, 9L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
