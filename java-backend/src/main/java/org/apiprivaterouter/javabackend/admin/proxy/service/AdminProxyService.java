package org.apiprivaterouter.javabackend.admin.proxy.service;

import org.apiprivaterouter.javabackend.admin.proxy.model.AdminDataImportError;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminDataImportResult;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminDataPayload;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminDataProxy;
import org.apiprivaterouter.javabackend.admin.proxy.model.AdminProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.BatchCreateProxiesRequest;
import org.apiprivaterouter.javabackend.admin.proxy.model.BatchCreateProxiesResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.BatchDeleteProxiesResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.BatchDeleteSkippedResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.CreateProxyRequest;
import org.apiprivaterouter.javabackend.admin.proxy.model.ProxyAccountSummaryResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.ProxyQualityCheckItemResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.ProxyQualityCheckResultResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.ProxyStatsResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.TestProxyResponse;
import org.apiprivaterouter.javabackend.admin.proxy.model.UpdateProxyRequest;
import org.apiprivaterouter.javabackend.admin.proxy.repository.AdminProxyRepository;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AdminProxyService {

    private static final String DATA_TYPE = "api-private-router-data";
    private static final int DATA_VERSION = 1;
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_INACTIVE = "inactive";

    private final AdminProxyRepository repository;
    private final AdminProxyProbeService probeService;

    public AdminProxyService(AdminProxyRepository repository, AdminProxyProbeService probeService) {
        this.repository = repository;
        this.probeService = probeService;
    }

    public PageResponse<AdminProxyResponse> listProxies(
            int page,
            int pageSize,
            String protocol,
            String status,
            String search,
            String sortBy,
            String sortOrder
    ) {
        return repository.listProxies(
                normalizePage(page),
                normalizePageSize(pageSize),
                normalizeProtocol(protocol, true),
                normalizeStatus(status, true),
                normalizeSearch(search),
                sortBy,
                sortOrder
        );
    }

    public List<AdminProxyResponse> listAll(String protocol, boolean withCount) {
        return repository.listAllActive(normalizeProtocol(protocol, true), withCount);
    }

    public AdminProxyResponse getProxy(long id) {
        return repository.getProxy(id).orElseThrow(() -> new HttpStatusException(404, "proxy not found"));
    }

    public AdminProxyResponse createProxy(CreateProxyRequest request) {
        long id = repository.createProxy(
                normalizeName(request.name()),
                normalizeProtocol(request.protocol(), false),
                normalizeHost(request.host()),
                normalizePort(request.port()),
                normalizeNullableText(request.username()),
                normalizeNullableText(request.password())
        );
        return getProxy(id);
    }

    public AdminProxyResponse updateProxy(long id, UpdateProxyRequest request) {
        getProxy(id);
        String name = request.isNamePresent() ? normalizeName(request.getName()) : null;
        String protocol = request.isProtocolPresent() ? normalizeProtocol(request.getProtocol(), false) : null;
        String host = request.isHostPresent() ? normalizeHost(request.getHost()) : null;
        Integer port = request.isPortPresent() ? normalizePort(request.getPort()) : null;
        String username = request.isUsernamePresent() ? normalizeNullableText(request.getUsername()) : null;
        String password = request.isPasswordPresent() ? normalizeNullableText(request.getPassword()) : null;
        String status = request.isStatusPresent() ? normalizeStatus(request.getStatus(), false) : null;
        int updated = repository.updateProxy(
                id,
                name,
                protocol,
                host,
                port,
                username,
                request.isUsernamePresent(),
                password,
                request.isPasswordPresent(),
                status
        );
        if (updated == 0) {
            throw new HttpStatusException(404, "proxy not found");
        }
        return getProxy(id);
    }

    public Map<String, String> deleteProxy(long id) {
        if (repository.countAccountsByProxyId(id) > 0) {
            throw new HttpStatusException(409, "proxy is in use by accounts");
        }
        int updated = repository.softDeleteProxy(id);
        if (updated == 0) {
            throw new HttpStatusException(404, "proxy not found");
        }
        return Map.of("message", "Proxy deleted successfully");
    }

    public TestProxyResponse testProxy(long id) {
        AdminProxyResponse proxy = getProxy(id);
        return probeService.testProxy(proxy);
    }

    public ProxyQualityCheckResultResponse checkProxyQuality(long id) {
        AdminProxyResponse proxy = getProxy(id);
        return probeService.checkProxyQuality(id, proxy);
    }

    public ProxyStatsResponse getStats(long id) {
        getProxy(id);
        return new ProxyStatsResponse(
                repository.countAccountsByProxyId(id),
                repository.countActiveAccountsByProxyId(id),
                0,
                100.0,
                0.0
        );
    }

    public List<ProxyAccountSummaryResponse> getProxyAccounts(long id) {
        getProxy(id);
        return repository.getProxyAccounts(id);
    }

    public BatchCreateProxiesResponse batchCreate(BatchCreateProxiesRequest request) {
        int created = 0;
        int skipped = 0;
        for (var item : request.proxies()) {
            String host = normalizeHost(item.host());
            int port = normalizePort(item.port());
            String username = normalizeNullableText(item.username());
            String password = normalizeNullableText(item.password());
            if (repository.existsByHostPortAuth(host, port, username, password)) {
                skipped++;
                continue;
            }
            repository.createProxy(
                    "default",
                    normalizeProtocol(item.protocol(), false),
                    host,
                    port,
                    username,
                    password
            );
            created++;
        }
        return new BatchCreateProxiesResponse(created, skipped);
    }

    public BatchDeleteProxiesResponse batchDelete(List<Long> ids) {
        List<Long> deletedIds = new ArrayList<>();
        List<BatchDeleteSkippedResponse> skipped = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                continue;
            }
            long accountCount = repository.countAccountsByProxyId(id);
            if (accountCount > 0) {
                skipped.add(new BatchDeleteSkippedResponse(id, "proxy is in use by accounts"));
                continue;
            }
            int updated = repository.softDeleteProxy(id);
            if (updated == 0) {
                skipped.add(new BatchDeleteSkippedResponse(id, "proxy not found"));
                continue;
            }
            deletedIds.add(id);
        }
        return new BatchDeleteProxiesResponse(deletedIds, skipped);
    }

    public AdminDataPayload exportData(
            List<Long> ids,
            String protocol,
            String status,
            String search,
            String sortBy,
            String sortOrder
    ) {
        List<AdminProxyResponse> proxies = ids != null && !ids.isEmpty()
                ? repository.listByIds(ids)
                : collectAllFiltered(protocol, status, search, sortBy, sortOrder);
        List<AdminDataProxy> items = proxies.stream().map(proxy -> new AdminDataProxy(
                buildProxyKey(proxy.protocol(), proxy.host(), proxy.port(), proxy.username(), proxy.password()),
                proxy.name(),
                proxy.protocol(),
                proxy.host(),
                proxy.port(),
                proxy.username(),
                proxy.password(),
                proxy.status()
        )).toList();
        return new AdminDataPayload(
                DATA_TYPE,
                DATA_VERSION,
                Instant.now().toString(),
                items,
                List.of()
        );
    }

    public AdminDataImportResult importData(AdminDataPayload payload) {
        validateDataHeader(payload);
        int proxyCreated = 0;
        int proxyReused = 0;
        int proxyFailed = 0;
        List<AdminDataImportError> errors = new ArrayList<>();
        List<AdminProxyResponse> existingProxies = collectAllFiltered(null, null, null, "id", "desc");
        Map<String, AdminProxyResponse> proxyByKey = new java.util.LinkedHashMap<>();
        for (AdminProxyResponse proxy : existingProxies) {
            proxyByKey.put(buildProxyKey(proxy.protocol(), proxy.host(), proxy.port(), proxy.username(), proxy.password()), proxy);
        }

        for (AdminDataProxy item : payload.proxies()) {
            String key = item.proxy_key() == null || item.proxy_key().isBlank()
                    ? buildProxyKey(item.protocol(), item.host(), item.port(), item.username(), item.password())
                    : item.proxy_key();
            try {
                validateDataProxy(item);
                String normalizedStatus = normalizeStatus(item.status(), true);
                AdminProxyResponse existing = proxyByKey.get(key);
                if (existing != null) {
                    proxyReused++;
                    if (normalizedStatus != null && !normalizedStatus.equals(existing.status())) {
                        repository.updateProxy(existing.id(), null, null, null, null, null, false, null, false, normalizedStatus);
                    }
                    continue;
                }
                long createdId = repository.createProxy(
                        defaultProxyName(item.name()),
                        normalizeProtocol(item.protocol(), false),
                        normalizeHost(item.host()),
                        normalizePort(item.port()),
                        normalizeNullableText(item.username()),
                        normalizeNullableText(item.password())
                );
                if (normalizedStatus != null && !STATUS_ACTIVE.equals(normalizedStatus)) {
                    repository.updateProxy(createdId, null, null, null, null, null, false, null, false, normalizedStatus);
                }
                AdminProxyResponse created = getProxy(createdId);
                proxyByKey.put(key, created);
                proxyCreated++;
            } catch (Exception ex) {
                proxyFailed++;
                errors.add(new AdminDataImportError("proxy", item.name(), key, ex.getMessage()));
            }
        }

        return new AdminDataImportResult(proxyCreated, proxyReused, proxyFailed, 0, 0, errors.isEmpty() ? null : errors);
    }

    private List<AdminProxyResponse> collectAllFiltered(String protocol, String status, String search, String sortBy, String sortOrder) {
        int page = 1;
        int pageSize = 1000;
        List<AdminProxyResponse> out = new ArrayList<>();
        while (true) {
            PageResponse<AdminProxyResponse> response = listProxies(page, pageSize, protocol, status, search, sortBy, sortOrder);
            out.addAll(response.items());
            if (out.size() >= response.total() || response.items().isEmpty()) {
                break;
            }
            page++;
        }
        return out;
    }

    private void validateDataHeader(AdminDataPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("data is required");
        }
        if (payload.type() != null && !payload.type().isBlank() && !DATA_TYPE.equals(payload.type())) {
            throw new IllegalArgumentException("unsupported data type: " + payload.type());
        }
        if (payload.version() != null && payload.version() != 0 && payload.version() != DATA_VERSION) {
            throw new IllegalArgumentException("unsupported data version: " + payload.version());
        }
        if (payload.proxies() == null) {
            throw new IllegalArgumentException("proxies is required");
        }
        if (payload.accounts() == null) {
            throw new IllegalArgumentException("accounts is required");
        }
    }

    private void validateDataProxy(AdminDataProxy item) {
        normalizeProtocol(item.protocol(), false);
        normalizeHost(item.host());
        normalizePort(item.port());
        if (item.status() != null && !item.status().isBlank()) {
            normalizeStatus(item.status(), false);
        }
    }

    private String defaultProxyName(String name) {
        String normalized = normalizeNullableText(name);
        return normalized == null || normalized.isBlank() ? "imported-proxy" : normalized;
    }

    private String buildProxyKey(String protocol, String host, int port, String username, String password) {
        return String.join("|",
                defaultString(protocol),
                defaultString(host),
                String.valueOf(port),
                defaultString(username),
                defaultString(password));
    }

    private String normalizeName(String value) {
        String normalized = normalizeNullableText(value);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        return normalized;
    }

    private String normalizeHost(String value) {
        String normalized = normalizeNullableText(value);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        return normalized;
    }

    private String normalizeProtocol(String value, boolean allowNull) {
        String normalized = normalizeNullableText(value);
        if (normalized == null || normalized.isBlank()) {
            if (allowNull) {
                return null;
            }
            throw new IllegalArgumentException("protocol is required");
        }
        normalized = normalized.toLowerCase();
        if (!List.of("http", "https", "socks5", "socks5h").contains(normalized)) {
            throw new IllegalArgumentException("protocol is invalid");
        }
        return normalized;
    }

    private String normalizeStatus(String value, boolean allowNull) {
        String normalized = normalizeNullableText(value);
        if (normalized == null || normalized.isBlank()) {
            if (allowNull) {
                return null;
            }
            throw new IllegalArgumentException("status is required");
        }
        normalized = normalized.toLowerCase();
        if (STATUS_ACTIVE.equals(normalized)) {
            return STATUS_ACTIVE;
        }
        if (STATUS_INACTIVE.equals(normalized) || "disabled".equals(normalized)) {
            return STATUS_INACTIVE;
        }
        throw new IllegalArgumentException("status is invalid");
    }

    private int normalizePort(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("port is invalid");
        }
        return port;
    }

    private int normalizePage(int page) {
        return Math.max(page, 1);
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return 20;
        }
        return Math.min(pageSize, 200);
    }

    private String normalizeSearch(String search) {
        String normalized = normalizeNullableText(search);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }

    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }
}
