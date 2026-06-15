package org.apiprivaterouter.javabackend.admin.backups.service;

import org.apiprivaterouter.javabackend.admin.backups.model.BackupS3Config;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Component
class S3CompatibleBackupStoreFactory {

    public BackupObjectStore create(BackupS3Config config) {
        return new S3CompatibleBackupStore(config);
    }
}

class S3CompatibleBackupStore implements BackupObjectStore {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final HexFormat HEX = HexFormat.of();

    private final BackupS3Config config;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI endpointUri;
    private final String region;
    private final boolean pathStyle;

    S3CompatibleBackupStore(BackupS3Config config) {
        this.config = config;
        this.endpointUri = normalizeEndpoint(config.endpoint());
        this.region = config.region() == null || config.region().isBlank() ? "auto" : config.region().trim();
        this.pathStyle = Boolean.TRUE.equals(config.force_path_style());
    }

    // TODO: Replace readAllBytes with a streaming upload approach (e.g. chunked
    // transfer or multipart upload) to avoid loading the entire backup file into
    // memory. Large backups will cause OOM with the current implementation.
    @Override
    public long upload(String key, InputStream body, String contentType) throws IOException, InterruptedException {
        byte[] bytes = readAllBytes(body);
        URI uri = objectUri(key);
        Instant now = Instant.now();
        Map<String, String> headers = baseHeaders(contentType == null ? "application/octet-stream" : contentType, bytes, now);
        sign("PUT", uri, Map.of(), headers, now, sha256Hex(bytes));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        headers.forEach(builder::header);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(response, "upload backup");
        return bytes.length;
    }

    @Override
    public InputStream download(String key) throws IOException, InterruptedException {
        URI uri = objectUri(key);
        Instant now = Instant.now();
        Map<String, String> headers = baseHeaders("", new byte[0], now);
        sign("GET", uri, Map.of(), headers, now, "UNSIGNED-PAYLOAD");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .GET();
        headers.forEach(builder::header);
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        requireSuccess(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8), "download backup");
        return new ByteArrayInputStream(response.body());
    }

    @Override
    public void delete(String key) throws IOException, InterruptedException {
        URI uri = objectUri(key);
        Instant now = Instant.now();
        Map<String, String> headers = baseHeaders("", new byte[0], now);
        sign("DELETE", uri, Map.of(), headers, now, "UNSIGNED-PAYLOAD");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .DELETE();
        headers.forEach(builder::header);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 404) {
            requireSuccess(response, "delete backup");
        }
    }

    @Override
    public String presignUrl(String key, Duration expiry) {
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.withZone(ZoneOffset.UTC).format(now);
        String date = DATE.withZone(ZoneOffset.UTC).format(now);
        String scope = date + "/" + region + "/s3/aws4_request";
        URI uri = objectUri(key);

        Map<String, String> query = new TreeMap<>();
        query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        query.put("X-Amz-Credential", config.access_key_id() + "/" + scope);
        query.put("X-Amz-Date", amzDate);
        query.put("X-Amz-Expires", String.valueOf(Math.max(1L, Math.min(expiry.getSeconds(), 604800L))));
        query.put("X-Amz-SignedHeaders", "host");

        String canonicalRequest = String.join("\n",
                "GET",
                canonicalUri(uri),
                canonicalQuery(query),
                "host:" + uri.getHost() + "\n",
                "host",
                "UNSIGNED-PAYLOAD");
        String stringToSign = String.join("\n",
                "AWS4-HMAC-SHA256",
                amzDate,
                scope,
                sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        String signature = hmacHex(signingKey(date), stringToSign);
        query.put("X-Amz-Signature", signature);
        return uri.getScheme() + "://" + uri.getAuthority() + canonicalUri(uri) + "?" + canonicalQuery(query);
    }

    @Override
    public void headBucket() throws IOException, InterruptedException {
        URI uri = bucketUri();
        Instant now = Instant.now();
        Map<String, String> headers = baseHeaders("", new byte[0], now);
        sign("HEAD", uri, Map.of(), headers, now, "UNSIGNED-PAYLOAD");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .method("HEAD", HttpRequest.BodyPublishers.noBody());
        headers.forEach(builder::header);
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(response, "test S3 connection");
    }

    private Map<String, String> baseHeaders(String contentType, byte[] body, Instant now) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", objectHost());
        headers.put("x-amz-date", AMZ_DATE.withZone(ZoneOffset.UTC).format(now));
        headers.put("x-amz-content-sha256", body.length == 0 ? "UNSIGNED-PAYLOAD" : sha256Hex(body));
        if (contentType != null && !contentType.isBlank()) {
            headers.put("content-type", contentType);
        }
        return headers;
    }

    private void sign(String method, URI uri, Map<String, String> query, Map<String, String> headers, Instant now, String payloadHash) {
        String date = DATE.withZone(ZoneOffset.UTC).format(now);
        String amzDate = AMZ_DATE.withZone(ZoneOffset.UTC).format(now);
        Map<String, String> sortedHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sortedHeaders.putAll(headers);
        sortedHeaders.put("host", uri.getHost());
        String signedHeaders = sortedHeaders.keySet().stream()
                .map(k -> k.toLowerCase(Locale.ROOT))
                .sorted()
                .reduce((a, b) -> a + ";" + b)
                .orElse("host");
        String canonicalHeaders = sortedHeaders.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> entry.getKey().toLowerCase(Locale.ROOT) + ":" + entry.getValue().trim() + "\n")
                .reduce("", String::concat);
        String scope = date + "/" + region + "/s3/aws4_request";
        String canonicalRequest = String.join("\n",
                method,
                canonicalUri(uri),
                canonicalQuery(query),
                canonicalHeaders,
                signedHeaders,
                payloadHash);
        String stringToSign = String.join("\n",
                "AWS4-HMAC-SHA256",
                amzDate,
                scope,
                sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + config.access_key_id() + "/" + scope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + hmacHex(signingKey(date), stringToSign);
        headers.put("Authorization", authorization);
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-content-sha256", payloadHash);
    }

    private byte[] signingKey(String date) {
        byte[] kDate = hmacBytes(("AWS4" + config.secret_access_key()).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmacBytes(kDate, region);
        byte[] kService = hmacBytes(kRegion, "s3");
        return hmacBytes(kService, "aws4_request");
    }

    private URI bucketUri() {
        return pathStyle
                ? endpointUri.resolve("/" + encodePathSegment(config.bucket()))
                : URI.create(endpointUri.getScheme() + "://" + config.bucket() + "." + endpointUri.getHost()
                + portSuffix(endpointUri) + "/");
    }

    private URI objectUri(String key) {
        String normalizedKey = key.startsWith("/") ? key.substring(1) : key;
        if (pathStyle) {
            return endpointUri.resolve("/" + encodePathSegment(config.bucket()) + "/" + encodePath(normalizedKey));
        }
        return URI.create(endpointUri.getScheme() + "://" + config.bucket() + "." + endpointUri.getHost()
                + portSuffix(endpointUri) + "/" + encodePath(normalizedKey));
    }

    private String objectHost() {
        return pathStyle ? endpointUri.getHost() + portSuffix(endpointUri) : config.bucket() + "." + endpointUri.getHost() + portSuffix(endpointUri);
    }

    private static URI normalizeEndpoint(String endpoint) {
        String raw = endpoint == null || endpoint.isBlank() ? "" : endpoint.trim();
        if (raw.isBlank()) {
            throw new IllegalArgumentException("endpoint is required");
        }
        URI uri = URI.create(raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("invalid S3 endpoint");
        }
        return uri;
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private static String canonicalUri(URI uri) {
        return uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
    }

    private static String canonicalQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(query.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries) {
            parts.add(urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()));
        }
        return String.join("&", parts);
    }

    private static String encodePath(String key) {
        String[] parts = key.split("/");
        List<String> encoded = new ArrayList<>(parts.length);
        for (String part : parts) {
            encoded.add(encodePathSegment(part));
        }
        return String.join("/", encoded);
    }

    private static String encodePathSegment(String value) {
        return urlEncode(value).replace("+", "%20");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to hash payload", ex);
        }
    }

    private static byte[] hmacBytes(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to sign request", ex);
        }
    }

    private static String hmacHex(byte[] key, String message) {
        return HEX.formatHex(hmacBytes(key, message));
    }

    private static String portSuffix(URI uri) {
        return uri.getPort() > 0 ? ":" + uri.getPort() : "";
    }

    private static void requireSuccess(HttpResponse<String> response, String action) throws IOException {
        requireSuccess(response.statusCode(), response.body(), action);
    }

    private static void requireSuccess(int status, String body, String action) throws IOException {
        if (status < 200 || status >= 300) {
            throw new IOException(action + " failed: HTTP " + status + (body == null || body.isBlank() ? "" : " " + body));
        }
    }
}
