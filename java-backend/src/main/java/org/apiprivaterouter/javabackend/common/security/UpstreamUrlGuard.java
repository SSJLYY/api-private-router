package org.apiprivaterouter.javabackend.common.security;

import org.apiprivaterouter.javabackend.common.config.UrlAllowlistProperties;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class UpstreamUrlGuard {

    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "localhost",
            "localhost.localdomain",
            "metadata",
            "metadata.google.internal",
            "metadata.goog",
            "instance-data",
            "instance-data.ec2.internal"
    );

    private static final Set<String> OPENAI_DEFAULT_HOSTS = Set.of(
            "api.openai.com",
            "chatgpt.com",
            "*.openai.azure.com"
    );

    private static final Set<String> ANTHROPIC_DEFAULT_HOSTS = Set.of(
            "api.anthropic.com"
    );

    private static final Set<String> GEMINI_DEFAULT_HOSTS = Set.of(
            "generativelanguage.googleapis.com",
            "cloudcode-pa.googleapis.com",
            "aiplatform.googleapis.com",
            "*.aiplatform.googleapis.com"
    );

    private final UrlAllowlistProperties properties;

    public UpstreamUrlGuard(UrlAllowlistProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> normalizeAccountCredentials(String platform, String type, Map<String, Object> input) {
        Map<String, Object> credentials = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        if (!credentials.containsKey("base_url")) {
            return credentials;
        }
        String raw = trimToNull(String.valueOf(credentials.get("base_url")));
        if (raw == null) {
            credentials.remove("base_url");
            return credentials;
        }
        credentials.put("base_url", normalizeAccountBaseUrl(platform, type, raw, null));
        return credentials;
    }

    public void validateAccountCredentialsPatch(String platform, String type, Map<String, Object> current, Map<String, Object> patch) {
        if (patch == null || !patch.containsKey("base_url")) {
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        merged.putAll(patch);
        normalizeAccountCredentials(platform, type, merged);
    }

    public String normalizeAccountBaseUrl(String platform, String type, String raw, String fallback) {
        String candidate = firstNonBlank(raw, fallback);
        if (candidate == null) {
            return null;
        }
        return validateAbsoluteUrl(
                candidate,
                allowedHostsForAccount(platform, type),
                Boolean.TRUE.equals(properties.enabled()),
                "base_url"
        );
    }

    public String normalizeCrsBaseUrl(String raw) {
        return validateAbsoluteUrl(
                raw,
                properties.crsHosts(),
                Boolean.TRUE.equals(properties.enabled()),
                "base_url"
        );
    }

    public String normalizePublicApiBaseUrl(String raw) {
        String normalized = trimToNull(raw);
        if (normalized == null) {
            return "";
        }
        if (normalized.startsWith("/")) {
            return trimTrailingSlash(normalized);
        }
        return validateAbsoluteUrl(normalized, List.of(), false, "api_base_url");
    }

    private String validateAbsoluteUrl(String raw, List<String> allowedHosts, boolean enforceAllowlist, String label) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid " + label + ": invalid url");
        }
        String scheme = trimToNull(uri.getScheme());
        String host = trimToNull(uri.getHost());
        if (scheme == null || host == null) {
            throw new IllegalArgumentException("invalid " + label + ": host is required");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"https".equals(normalizedScheme)
                && !(Boolean.TRUE.equals(properties.allowInsecureHttp()) && "http".equals(normalizedScheme))) {
            throw new IllegalArgumentException("invalid " + label + ": unsupported scheme");
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new IllegalArgumentException("invalid " + label + ": userinfo is not allowed");
        }
        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            throw new IllegalArgumentException("invalid " + label + ": query is not allowed");
        }
        if (uri.getFragment() != null && !uri.getFragment().isBlank()) {
            throw new IllegalArgumentException("invalid " + label + ": fragment is not allowed");
        }
        if (uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("invalid " + label + ": invalid port");
        }

        validateHost(host.toLowerCase(Locale.ROOT), label);

        List<String> allowlist = normalizeAllowlist(allowedHosts);
        if (enforceAllowlist) {
            if (allowlist.isEmpty()) {
                throw new IllegalArgumentException("invalid " + label + ": allowlist is not configured");
            }
            if (!isAllowedHost(host.toLowerCase(Locale.ROOT), allowlist)) {
                throw new IllegalArgumentException("invalid " + label + ": host is not allowed");
            }
        }
        return trimTrailingSlash(trimmed);
    }

    private void validateHost(String host, String label) {
        if (Boolean.TRUE.equals(properties.allowPrivateHosts())) {
            return;
        }
        if (BLOCKED_HOSTNAMES.contains(host) || host.endsWith(".localhost") || host.endsWith(".local")) {
            throw new IllegalArgumentException("invalid " + label + ": host is not allowed");
        }
        InetAddress address = parseIpLiteral(host);
        if (address != null && isPrivateAddress(address)) {
            throw new IllegalArgumentException("invalid " + label + ": host is not allowed");
        }
    }

    private List<String> allowedHostsForAccount(String platform, String type) {
        LinkedHashSet<String> hosts = new LinkedHashSet<>(properties.upstreamHosts());
        String normalizedPlatform = trimToNull(platform) == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
        String normalizedType = trimToNull(type) == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        switch (normalizedPlatform) {
            case "openai" -> hosts.addAll(OPENAI_DEFAULT_HOSTS);
            case "anthropic", "claude" -> hosts.addAll(ANTHROPIC_DEFAULT_HOSTS);
            case "gemini" -> hosts.addAll(GEMINI_DEFAULT_HOSTS);
            case "antigravity" -> {
                if (!"apikey".equals(normalizedType)) {
                    hosts.addAll(GEMINI_DEFAULT_HOSTS);
                    hosts.addAll(ANTHROPIC_DEFAULT_HOSTS);
                }
            }
            default -> {
            }
        }
        return List.copyOf(hosts);
    }

    private List<String> normalizeAllowlist(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String entry = trimToNull(value);
            if (entry == null) {
                continue;
            }
            normalized.add(entry.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private boolean isAllowedHost(String host, List<String> allowlist) {
        for (String entry : allowlist) {
            if (entry.startsWith("*.")) {
                String suffix = entry.substring(2);
                if (host.equals(suffix) || host.endsWith("." + suffix)) {
                    return true;
                }
                continue;
            }
            if (host.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    private InetAddress parseIpLiteral(String host) {
        try {
            if (host.contains(":") || host.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                return InetAddress.getByName(host);
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isPrivateAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            if (first == 0 || first == 127) {
                return true;
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            if (first == 169 && second == 254) {
                return true;
            }
        }
        if (bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            if ((first & 0xfe) == 0xfc) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String trimTrailingSlash(String value) {
        String normalized = value.trim();
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
