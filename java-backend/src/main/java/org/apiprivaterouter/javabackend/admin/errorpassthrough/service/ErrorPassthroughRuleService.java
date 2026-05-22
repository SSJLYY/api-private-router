package org.apiprivaterouter.javabackend.admin.errorpassthrough.service;

import org.apiprivaterouter.javabackend.admin.errorpassthrough.model.CreateErrorPassthroughRuleRequest;
import org.apiprivaterouter.javabackend.admin.errorpassthrough.model.ErrorPassthroughRuleRecord;
import org.apiprivaterouter.javabackend.admin.errorpassthrough.model.ErrorPassthroughRuleResponse;
import org.apiprivaterouter.javabackend.admin.errorpassthrough.model.UpdateErrorPassthroughRuleRequest;
import org.apiprivaterouter.javabackend.admin.errorpassthrough.repository.ErrorPassthroughRuleRepository;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class ErrorPassthroughRuleService {

    private static final String MATCH_MODE_ANY = "any";
    private static final String MATCH_MODE_ALL = "all";

    private final ErrorPassthroughRuleRepository repository;

    public ErrorPassthroughRuleService(ErrorPassthroughRuleRepository repository) {
        this.repository = repository;
    }

    public List<ErrorPassthroughRuleResponse> list() {
        return repository.list().stream().map(this::toResponse).toList();
    }

    public ErrorPassthroughRuleResponse getById(long id) {
        return repository.getById(id)
                .map(this::toResponse)
                .orElseThrow(this::notFound);
    }

    public ErrorPassthroughRuleResponse create(CreateErrorPassthroughRuleRequest request) {
        ErrorPassthroughRuleRecord record = new ErrorPassthroughRuleRecord(
                0L,
                requireName(request.name()),
                request.enabled() == null || request.enabled(),
                request.priority() == null ? 0 : request.priority(),
                normalizeErrorCodes(request.error_codes()),
                normalizeKeywords(request.keywords()),
                normalizeMatchMode(request.match_mode(), MATCH_MODE_ANY),
                normalizePlatforms(request.platforms()),
                request.passthrough_code() == null || request.passthrough_code(),
                request.response_code(),
                request.passthrough_body() == null || request.passthrough_body(),
                normalizeNullableText(request.custom_message()),
                request.skip_monitoring() != null && request.skip_monitoring(),
                normalizeNullableText(request.description()),
                null,
                null
        );
        validate(record);
        return toResponse(repository.create(record));
    }

    public ErrorPassthroughRuleResponse update(long id, UpdateErrorPassthroughRuleRequest request) {
        ErrorPassthroughRuleRecord existing = repository.getById(id)
                .orElseThrow(this::notFound);
        ErrorPassthroughRuleRecord merged = new ErrorPassthroughRuleRecord(
                id,
                request.name() != null ? requireName(request.name()) : existing.name(),
                request.enabled() != null ? request.enabled() : existing.enabled(),
                request.priority() != null ? request.priority() : existing.priority(),
                request.error_codes() != null ? normalizeErrorCodes(request.error_codes()) : existing.errorCodes(),
                request.keywords() != null ? normalizeKeywords(request.keywords()) : existing.keywords(),
                request.match_mode() != null ? normalizeMatchMode(request.match_mode(), existing.matchMode()) : existing.matchMode(),
                request.platforms() != null ? normalizePlatforms(request.platforms()) : existing.platforms(),
                request.passthrough_code() != null ? request.passthrough_code() : existing.passthroughCode(),
                request.response_code() != null ? request.response_code() : existing.responseCode(),
                request.passthrough_body() != null ? request.passthrough_body() : existing.passthroughBody(),
                request.custom_message() != null ? normalizeNullableText(request.custom_message()) : existing.customMessage(),
                request.skip_monitoring() != null ? request.skip_monitoring() : existing.skipMonitoring(),
                request.description() != null ? normalizeNullableText(request.description()) : existing.description(),
                existing.createdAt(),
                existing.updatedAt()
        );
        validate(merged);
        return toResponse(repository.update(merged));
    }

    public void delete(long id) {
        if (!repository.delete(id)) {
            throw notFound();
        }
    }

    private void validate(ErrorPassthroughRuleRecord rule) {
        if (rule.name().isBlank()) {
            throw validation("name", "name is required");
        }
        if (!MATCH_MODE_ANY.equals(rule.matchMode()) && !MATCH_MODE_ALL.equals(rule.matchMode())) {
            throw validation("match_mode", "match_mode must be 'any' or 'all'");
        }
        if (rule.errorCodes().isEmpty() && rule.keywords().isEmpty()) {
            throw validation("conditions", "at least one error_code or keyword is required");
        }
        if (!rule.passthroughCode()) {
            if (rule.responseCode() == null || rule.responseCode() <= 0) {
                throw validation("response_code", "response_code is required when passthrough_code is false");
            }
        }
        if (!rule.passthroughBody()) {
            if (rule.customMessage() == null || rule.customMessage().isBlank()) {
                throw validation("custom_message", "custom_message is required when passthrough_body is false");
            }
        }
    }

    private StructuredApiErrorException validation(String field, String message) {
        return new StructuredApiErrorException(400, "VALIDATION_ERROR", field + ": " + message);
    }

    private StructuredApiErrorException notFound() {
        return new StructuredApiErrorException(404, "NOT_FOUND", "rule not found");
    }

    private ErrorPassthroughRuleResponse toResponse(ErrorPassthroughRuleRecord record) {
        return new ErrorPassthroughRuleResponse(
                record.id(),
                record.name(),
                record.enabled(),
                record.priority(),
                record.errorCodes(),
                record.keywords(),
                record.matchMode(),
                record.platforms(),
                record.passthroughCode(),
                record.responseCode(),
                record.passthroughBody(),
                record.customMessage(),
                record.skipMonitoring(),
                record.description(),
                toIso(record.createdAt()),
                toIso(record.updatedAt())
        );
    }

    private String requireName(String raw) {
        String name = normalizeNullableText(raw);
        if (name == null) {
            throw validation("name", "name is required");
        }
        return name;
    }

    private List<Integer> normalizeErrorCodes(List<Integer> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(item -> item != null).distinct().toList();
    }

    private List<String> normalizeKeywords(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(this::normalizeNullableText)
                .filter(item -> item != null)
                .distinct()
                .toList();
    }

    private List<String> normalizePlatforms(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(this::normalizeNullableText)
                .filter(item -> item != null)
                .map(item -> item.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String normalizeMatchMode(String raw, String defaultValue) {
        String normalized = normalizeNullableText(raw);
        return normalized == null ? defaultValue : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeNullableText(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String toIso(OffsetDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
