package org.apiprivaterouter.javabackend.common.frontend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apiprivaterouter.javabackend.common.config.DataDirectoryProperties;
import org.apiprivaterouter.javabackend.common.config.FrontendAssetsProperties;
import org.apiprivaterouter.javabackend.publicsettings.model.PublicSettingsResponse;
import org.apiprivaterouter.javabackend.publicsettings.service.PublicSettingsService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class FrontendStaticService {

    public static final String NONCE_PLACEHOLDER = "__CSP_NONCE_VALUE__";

    private final Path overrideDir;
    private final List<Path> distCandidates;
    private final ObjectMapper objectMapper;
    private final PublicSettingsService publicSettingsService;
    private final AtomicReference<CachedIndexHtml> cachedIndexHtml = new AtomicReference<>();

    public FrontendStaticService(
            DataDirectoryProperties dataDirectoryProperties,
            FrontendAssetsProperties frontendAssetsProperties,
            ObjectMapper objectMapper,
            PublicSettingsService publicSettingsService
    ) {
        this.overrideDir = Path.of(dataDirectoryProperties.resolvedDir()).toAbsolutePath().normalize().resolve("public").normalize();
        this.distCandidates = buildDistCandidates(frontendAssetsProperties);
        this.objectMapper = objectMapper;
        this.publicSettingsService = publicSettingsService;
    }

    public Optional<StaticAsset> loadAsset(String requestPath) {
        String relativePath = normalizeRelativePath(requestPath);
        if (relativePath.isBlank()) {
            return Optional.empty();
        }

        Path overrideAsset = resolveChild(overrideDir, relativePath);
        if (overrideAsset != null && Files.isRegularFile(overrideAsset, LinkOption.NOFOLLOW_LINKS)) {
            return readAsset(overrideAsset, relativePath);
        }

        Path distRoot = resolveDistRoot();
        if (distRoot == null) {
            return Optional.empty();
        }
        Path distAsset = resolveChild(distRoot, relativePath);
        if (distAsset == null || !Files.isRegularFile(distAsset, LinkOption.NOFOLLOW_LINKS)) {
            return readClasspathAsset(relativePath);
        }
        return readAsset(distAsset, relativePath);
    }

    public Optional<StaticAsset> loadIndexHtml() {
        Path distRoot = resolveDistRoot();
        if (distRoot == null) {
            return Optional.empty();
        }
        Path indexPath = resolveChild(distRoot, "index.html");
        if (indexPath == null || !Files.isRegularFile(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            return loadClasspathIndexHtml();
        }
        try {
            byte[] raw = Files.readAllBytes(indexPath);
            return renderIndexHtml(raw);
        } catch (IOException ignored) {
            return loadClasspathIndexHtml();
        }
    }

    private Optional<StaticAsset> loadClasspathIndexHtml() {
        try {
            ClassPathResource resource = new ClassPathResource("static-dist/index.html");
            if (!resource.exists()) {
                return Optional.empty();
            }
            byte[] raw = resource.getInputStream().readAllBytes();
            return renderIndexHtml(raw);
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<StaticAsset> renderIndexHtml(byte[] raw) {
        try {
            PublicSettingsResponse settings = publicSettingsService.getPublicSettings();
            String settingsJson = objectMapper.writeValueAsString(settings);
            String contentKey = computeHash(raw) + ":" + computeHash(settingsJson.getBytes(StandardCharsets.UTF_8));
            CachedIndexHtml cached = cachedIndexHtml.get();
            if (cached != null && cached.cacheKey().equals(contentKey)) {
                return Optional.of(new StaticAsset("text/html; charset=utf-8", cached.content(), cached.etag(), true));
            }
            byte[] rendered = injectPublicSettings(raw, settingsJson, settings);
            String etag = "\"" + computeHash(raw).substring(0, 16) + "-" + computeHash(settingsJson.getBytes(StandardCharsets.UTF_8)).substring(0, 16) + "\"";
            CachedIndexHtml fresh = new CachedIndexHtml(contentKey, rendered, etag);
            cachedIndexHtml.set(fresh);
            return Optional.of(new StaticAsset("text/html; charset=utf-8", rendered, etag, true));
        } catch (JsonProcessingException ignored) {
            return Optional.empty();
        }
    }

    private List<Path> buildDistCandidates(FrontendAssetsProperties frontendAssetsProperties) {
        String configured = frontendAssetsProperties.resolvedDistDir();
        if (!configured.isBlank()) {
            return List.of(Path.of(configured).toAbsolutePath().normalize());
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path repoRoot = cwd.getFileName() != null && "java-backend".equalsIgnoreCase(cwd.getFileName().toString())
                ? cwd.getParent()
                : cwd;
        if (repoRoot == null) {
            repoRoot = cwd;
        }
        return List.of(
                repoRoot.resolve("frontend/dist").normalize(),
                repoRoot.resolve("dist").normalize(),
                cwd.resolve("frontend/dist").normalize(),
                cwd.resolve("dist").normalize()
        );
    }

    private Path resolveDistRoot() {
        for (Path candidate : distCandidates) {
            if (candidate == null) {
                continue;
            }
            Path index = resolveChild(candidate, "index.html");
            if (index != null && Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        return null;
    }

    private Optional<StaticAsset> readAsset(Path path, String relativePath) {
        try {
            MediaType contentType = MediaTypeFactory.getMediaType(relativePath)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
            return Optional.of(new StaticAsset(contentType.toString(), Files.readAllBytes(path), null, false));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<StaticAsset> readClasspathAsset(String relativePath) {
        try {
            ClassPathResource resource = new ClassPathResource("static-dist/" + relativePath);
            if (!resource.exists()) {
                return Optional.empty();
            }
            MediaType contentType = MediaTypeFactory.getMediaType(relativePath)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
            return Optional.of(new StaticAsset(contentType.toString(), resource.getInputStream().readAllBytes(), null, false));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private byte[] injectPublicSettings(byte[] html, String settingsJson, PublicSettingsResponse settings) {
        String markup = new String(html, StandardCharsets.UTF_8);
        String script = "<script nonce=\"" + NONCE_PLACEHOLDER + "\">window.__APP_CONFIG__=" + settingsJson + ";</script>";
        String rendered = markup.contains("</head>")
                ? markup.replace("</head>", script + System.lineSeparator() + "</head>")
                : script + markup;

        String siteName = settings.site_name();
        if (siteName != null && !siteName.isBlank()) {
            rendered = rendered.replaceAll(
                    "(?s)<title>.*?</title>",
                    "<title>" + escapeHtml(siteName.trim()) + " - AI API Gateway</title>"
            );
        }
        return rendered.getBytes(StandardCharsets.UTF_8);
    }

    private String computeHash(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String normalizeRelativePath(String requestPath) {
        if (requestPath == null || requestPath.isBlank()) {
            return "";
        }
        String normalized = requestPath.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private Path resolveChild(Path root, String relativePath) {
        if (root == null || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        return resolved;
    }

    public record StaticAsset(String contentType, byte[] content, String etag, boolean html) {
    }

    private record CachedIndexHtml(String cacheKey, byte[] content, String etag) {
    }
}
