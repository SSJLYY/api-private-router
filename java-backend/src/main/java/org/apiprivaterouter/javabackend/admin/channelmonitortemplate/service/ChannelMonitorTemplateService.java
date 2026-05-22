package org.apiprivaterouter.javabackend.admin.channelmonitortemplate.service;

import org.springframework.dao.DuplicateKeyException;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.ApplyChannelMonitorTemplateRequest;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.ApplyTemplateResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.AssociatedMonitorBriefResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.AssociatedMonitorsResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.ChannelMonitorTemplateResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.CreateChannelMonitorTemplateRequest;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.ListChannelMonitorTemplatesResponse;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model.UpdateChannelMonitorTemplateRequest;
import org.apiprivaterouter.javabackend.admin.channelmonitortemplate.repository.ChannelMonitorTemplateRepository;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ChannelMonitorTemplateService {

    private static final Set<String> ALLOWED_PROVIDERS = Set.of("openai", "anthropic", "gemini");
    private static final Set<String> ALLOWED_BODY_MODES = Set.of("off", "merge", "replace");
    private static final Set<String> FORBIDDEN_HEADER_NAMES = Set.of(
            "host", "content-length", "content-encoding", "transfer-encoding", "connection"
    );
    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9!#$%&'*+\\-.^_`|~]+$");

    private final ChannelMonitorTemplateRepository repository;

    public ChannelMonitorTemplateService(ChannelMonitorTemplateRepository repository) {
        this.repository = repository;
    }

    public ListChannelMonitorTemplatesResponse list(String provider) {
        String normalizedProvider = normalizeProviderFilter(provider);
        return new ListChannelMonitorTemplatesResponse(repository.list(normalizedProvider));
    }

    public ChannelMonitorTemplateResponse get(long id) {
        return repository.get(id).orElseThrow(() -> new HttpStatusException(404, "channel monitor template not found"));
    }

    public ChannelMonitorTemplateResponse create(CreateChannelMonitorTemplateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String provider = normalizeProvider(request.provider());
        String name = requireName(request.name());
        String description = normalizeDescription(request.description());
        Map<String, String> extraHeaders = normalizeHeaders(request.extra_headers());
        String bodyMode = normalizeBodyOverrideMode(request.body_override_mode());
        Map<String, Object> bodyOverride = normalizeBodyOverride(request.body_override());
        validateBodyMode(bodyMode, bodyOverride);
        try {
            return repository.create(name, provider, description, extraHeaders, bodyMode, bodyOverride);
        } catch (DuplicateKeyException ex) {
            throw new HttpStatusException(409, "channel monitor template already exists");
        }
    }

    public ChannelMonitorTemplateResponse update(long id, UpdateChannelMonitorTemplateRequest request) {
        ChannelMonitorTemplateResponse current = get(id);
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String name = current.name();
        if (request.name() != null) {
            name = requireName(request.name());
        }
        String description = current.description();
        if (request.description() != null) {
            description = normalizeDescription(request.description());
        }
        Map<String, String> extraHeaders = current.extra_headers();
        if (request.extra_headers() != null) {
            extraHeaders = normalizeHeaders(request.extra_headers());
        }
        String bodyMode = current.body_override_mode();
        if (request.body_override_mode() != null) {
            bodyMode = normalizeBodyOverrideMode(request.body_override_mode());
        }
        Map<String, Object> bodyOverride = current.body_override();
        if (request.body_override() != null) {
            bodyOverride = normalizeBodyOverride(request.body_override());
        }
        validateBodyMode(bodyMode, bodyOverride);
        try {
            return repository.update(id, name, description, extraHeaders, bodyMode, bodyOverride);
        } catch (DuplicateKeyException ex) {
            throw new HttpStatusException(409, "channel monitor template already exists");
        }
    }

    public void delete(long id) {
        get(id);
        repository.delete(id);
    }

    public ApplyTemplateResponse apply(long id, ApplyChannelMonitorTemplateRequest request) {
        get(id);
        if (request == null || request.monitor_ids() == null || request.monitor_ids().isEmpty()) {
            throw new IllegalArgumentException("monitor_ids must be a non-empty array");
        }
        long affected = repository.applyToMonitors(id, request.monitor_ids().stream()
                .filter(value -> value != null && value > 0)
                .distinct()
                .toList());
        return new ApplyTemplateResponse(affected);
    }

    public AssociatedMonitorsResponse listAssociatedMonitors(long id) {
        get(id);
        List<AssociatedMonitorBriefResponse> items = repository.listAssociatedMonitors(id);
        return new AssociatedMonitorsResponse(items);
    }

    private String normalizeProviderFilter(String provider) {
        if (provider == null || provider.trim().isEmpty()) {
            return null;
        }
        return normalizeProvider(provider);
    }

    private String normalizeProvider(String provider) {
        if (provider == null) {
            throw new IllegalArgumentException("template provider must be one of openai/anthropic/gemini");
        }
        String normalized = provider.trim().toLowerCase();
        if (!ALLOWED_PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException("template provider must be one of openai/anthropic/gemini");
        }
        return normalized;
    }

    private String requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("template name is required");
        }
        return name.trim();
    }

    private String normalizeDescription(String description) {
        return description == null ? "" : description.trim();
    }

    private Map<String, String> normalizeHeaders(Map<String, String> extraHeaders) {
        Map<String, String> result = new LinkedHashMap<>();
        if (extraHeaders == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!HEADER_NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException("header name contains invalid characters");
            }
            if (FORBIDDEN_HEADER_NAMES.contains(name.toLowerCase())) {
                throw new IllegalArgumentException("header name is forbidden (hop-by-hop or computed by HTTP client)");
            }
            result.put(name, entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private String normalizeBodyOverrideMode(String bodyOverrideMode) {
        String normalized = bodyOverrideMode == null || bodyOverrideMode.trim().isEmpty()
                ? "off"
                : bodyOverrideMode.trim().toLowerCase();
        if (!ALLOWED_BODY_MODES.contains(normalized)) {
            throw new IllegalArgumentException("body_override_mode must be one of off/merge/replace");
        }
        return normalized;
    }

    private Map<String, Object> normalizeBodyOverride(Map<String, Object> bodyOverride) {
        if (bodyOverride == null) {
            return null;
        }
        return new LinkedHashMap<>(bodyOverride);
    }

    private void validateBodyMode(String bodyMode, Map<String, Object> bodyOverride) {
        if (("merge".equals(bodyMode) || "replace".equals(bodyMode))
                && (bodyOverride == null || bodyOverride.isEmpty())) {
            throw new IllegalArgumentException("body_override is required when body_override_mode is merge or replace");
        }
    }
}
