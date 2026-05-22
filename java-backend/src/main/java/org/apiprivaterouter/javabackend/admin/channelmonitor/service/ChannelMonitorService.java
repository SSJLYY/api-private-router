package org.apiprivaterouter.javabackend.admin.channelmonitor.service;

import org.apiprivaterouter.javabackend.admin.channelmonitor.model.ChannelMonitorCheckResultResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.ChannelMonitorHistoryItemResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.ChannelMonitorHistoryResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.ChannelMonitorResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.CreateChannelMonitorRequest;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.ExtraModelStatusResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.ListChannelMonitorsResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.RunNowResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitor.model.UpdateChannelMonitorRequest;
import org.apiprivaterouter.javabackend.admin.channelmonitor.repository.ChannelMonitorRepository;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorCheckResult;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorHistoryEntry;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorRecord;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorSummary;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorUpdateCommand;
import org.apiprivaterouter.javabackend.channelmonitor.model.ChannelMonitorWriteRequest;
import org.apiprivaterouter.javabackend.channelmonitor.model.ExtraModelStatus;
import org.apiprivaterouter.javabackend.channelmonitor.service.ChannelMonitorCoreService;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChannelMonitorService {

    private final ChannelMonitorRepository repository;
    private final ChannelMonitorCoreService coreService;

    public ChannelMonitorService(ChannelMonitorRepository repository, ChannelMonitorCoreService coreService) {
        this.repository = repository;
        this.coreService = coreService;
    }

    public ListChannelMonitorsResponse list(int page, int pageSize, String provider, Boolean enabled, String search) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        long total = repository.core().countAll(provider, enabled, search);
        List<ChannelMonitorRecord> records = repository.core().listAll(
                provider,
                enabled,
                search,
                (normalizedPage - 1) * normalizedPageSize,
                normalizedPageSize
        );
        Map<Long, ChannelMonitorSummary> summaries = coreService.buildSummaries(records);
        List<ChannelMonitorResponse> items = records.stream()
                .map(record -> toResponse(record, summaries.get(record.id())))
                .toList();
        int pages = normalizedPageSize <= 0 ? 0 : (int) Math.ceil((double) total / normalizedPageSize);
        return new ListChannelMonitorsResponse(items, total, normalizedPage, normalizedPageSize, pages);
    }

    public ChannelMonitorResponse get(long id) {
        return toResponse(coreService.requireMonitor(id), null);
    }

    public ChannelMonitorResponse create(long createdBy, CreateChannelMonitorRequest request) {
        ChannelMonitorRecord record = coreService.create(new ChannelMonitorWriteRequest(
                request.name(),
                request.provider(),
                request.endpoint(),
                request.api_key(),
                request.primary_model(),
                request.extra_models(),
                request.group_name(),
                request.enabled() == null || request.enabled(),
                request.interval_seconds(),
                createdBy,
                request.template_id(),
                request.extra_headers(),
                request.body_override_mode(),
                request.body_override()
        ));
        return toResponse(record, null);
    }

    public ChannelMonitorResponse update(long id, UpdateChannelMonitorRequest request) {
        ChannelMonitorRecord record = coreService.update(id, new ChannelMonitorUpdateCommand(
                request.name(),
                request.name() != null,
                request.provider(),
                request.provider() != null,
                request.endpoint(),
                request.endpoint() != null,
                request.api_key(),
                request.api_key() != null,
                request.primary_model(),
                request.primary_model() != null,
                request.extra_models(),
                request.extra_models() != null,
                request.group_name(),
                request.group_name() != null,
                request.enabled(),
                request.enabled() != null,
                request.interval_seconds(),
                request.interval_seconds() != null,
                request.template_id(),
                request.template_id() != null,
                Boolean.TRUE.equals(request.clear_template()),
                request.extra_headers(),
                request.extra_headers() != null,
                request.body_override_mode(),
                request.body_override_mode() != null,
                request.body_override(),
                request.body_override() != null
        ));
        return toResponse(record, null);
    }

    public void delete(long id) {
        coreService.delete(id);
    }

    public RunNowResponse runNow(long id) {
        List<ChannelMonitorCheckResultResponse> items = coreService.runNow(id).stream()
                .map(this::toRunResult)
                .toList();
        return new RunNowResponse(items);
    }

    public ChannelMonitorHistoryResponse history(long id, String model, Integer limit) {
        coreService.requireMonitor(id);
        int normalizedLimit = coreService.normalizeHistoryLimit(limit);
        List<ChannelMonitorHistoryItemResponse> items = repository.core().findHistory(id, model, normalizedLimit).stream()
                .map(this::toHistoryItem)
                .toList();
        return new ChannelMonitorHistoryResponse(items);
    }

    private ChannelMonitorResponse toResponse(ChannelMonitorRecord record, ChannelMonitorSummary summary) {
        ChannelMonitorSummary effectiveSummary = summary;
        if (effectiveSummary == null) {
            effectiveSummary = coreService.buildSummaries(List.of(record)).get(record.id());
        }
        boolean decryptFailed = coreService.decryptFailed(record);
        String apiKeyMasked = decryptFailed ? "***" : coreService.decryptMaskedApiKey(record);
        List<ExtraModelStatusResponse> extras = effectiveSummary == null ? List.of() : effectiveSummary.extraModels().stream()
                .map(this::toExtraStatus)
                .toList();
        return new ChannelMonitorResponse(
                record.id(),
                record.name(),
                record.provider(),
                record.endpoint(),
                apiKeyMasked,
                decryptFailed,
                record.primaryModel(),
                record.extraModels(),
                record.groupName(),
                record.enabled(),
                record.intervalSeconds(),
                iso(record.lastCheckedAt()),
                record.createdBy(),
                iso(record.createdAt()),
                iso(record.updatedAt()),
                effectiveSummary == null ? "" : effectiveSummary.primaryStatus(),
                effectiveSummary == null ? null : effectiveSummary.primaryLatencyMs(),
                effectiveSummary == null ? 0D : effectiveSummary.availability7d(),
                extras,
                record.templateId(),
                record.extraHeaders(),
                record.bodyOverrideMode(),
                record.bodyOverride()
        );
    }

    private ExtraModelStatusResponse toExtraStatus(ExtraModelStatus status) {
        return new ExtraModelStatusResponse(status.model(), status.status(), status.latencyMs());
    }

    private ChannelMonitorCheckResultResponse toRunResult(ChannelMonitorCheckResult result) {
        return new ChannelMonitorCheckResultResponse(
                result.model(),
                result.status(),
                result.latencyMs(),
                result.pingLatencyMs(),
                result.message(),
                iso(result.checkedAt())
        );
    }

    private ChannelMonitorHistoryItemResponse toHistoryItem(ChannelMonitorHistoryEntry entry) {
        return new ChannelMonitorHistoryItemResponse(
                entry.id(),
                entry.model(),
                entry.status(),
                entry.latencyMs(),
                entry.pingLatencyMs(),
                entry.message(),
                iso(entry.checkedAt())
        );
    }

    private String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
