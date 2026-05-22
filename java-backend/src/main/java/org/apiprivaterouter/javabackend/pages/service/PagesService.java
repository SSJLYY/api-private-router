package org.apiprivaterouter.javabackend.pages.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class PagesService implements InitializingBean {

    private static final Pattern VALID_SLUG = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$");
    private static final long MAX_PAGE_BYTES = 1 << 20;

    private final Path pagesDir;
    private final PageVisibilityService pageVisibilityService;

    public PagesService(org.apiprivaterouter.javabackend.common.config.DataDirectoryProperties properties,
                        PageVisibilityService pageVisibilityService) {
        this.pagesDir = Path.of(properties.resolvedDir()).toAbsolutePath().normalize().resolve("pages").normalize();
        this.pageVisibilityService = pageVisibilityService;
    }

    @Override
    public void afterPropertiesSet() throws IOException {
        Files.createDirectories(pagesDir);
    }

    public List<String> listSlugs() {
        if (!Files.isDirectory(pagesDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(pagesDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .map(name -> name.substring(0, name.length() - 3))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    public PageContent loadPage(String slug, boolean admin) {
        Path target = resolveMarkdownFile(slug);
        PageVisibilityService.VisibilityResult visibility = pageVisibilityService.resolve(slug);
        if (!visibility.found() || (visibility.adminOnly() && !admin)) {
            throw new PageNotFoundException();
        }
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new PageNotFoundException();
            }
            long size = Files.size(target);
            if (size > MAX_PAGE_BYTES) {
                throw new IllegalArgumentException("page too large");
            }
            byte[] content = Files.readAllBytes(target);
            return new PageContent(content, "text/markdown; charset=utf-8");
        } catch (IOException ex) {
            throw new PageReadException(ex);
        }
    }

    public ImageContent loadImage(String slug, String rawFilename) {
        validateSlug(slug);
        PageVisibilityService.VisibilityResult visibility = pageVisibilityService.resolve(slug);
        if (!visibility.found() || visibility.adminOnly()) {
            throw new PageNotFoundException();
        }
        Path relative = cleanRelativeImagePath(rawFilename);
        Path baseDir = pagesDir.resolve(slug).normalize();
        Path candidate = baseDir.resolve(relative).normalize();
        if (!candidate.startsWith(baseDir) || !baseDir.startsWith(pagesDir)) {
            throw new PageNotFoundException();
        }
        try {
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new PageNotFoundException();
            }
            Resource resource = new FileSystemResource(candidate);
            String mediaType = MediaTypeFactory.getMediaType(candidate.getFileName().toString())
                    .orElse(MediaType.APPLICATION_OCTET_STREAM)
                    .toString();
            return new ImageContent(resource, mediaType);
        } catch (Exception ex) {
            throw new PageNotFoundException();
        }
    }

    private Path resolveMarkdownFile(String slug) {
        validateSlug(slug);
        Path target = pagesDir.resolve(slug + ".md").normalize();
        if (!target.startsWith(pagesDir)) {
            throw new IllegalArgumentException("invalid page slug");
        }
        return target;
    }

    private void validateSlug(String slug) {
        if (slug == null || !VALID_SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("invalid page slug");
        }
    }

    private Path cleanRelativeImagePath(String rawFilename) {
        if (rawFilename == null) {
            throw new PageNotFoundException();
        }
        String normalized = rawFilename.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.contains("\\") || normalized.indexOf('\0') >= 0) {
            throw new PageNotFoundException();
        }
        String[] parts = normalized.split("/");
        Path relative = Path.of("");
        boolean sawSegment = false;
        for (String part : parts) {
            if (part == null || part.isBlank() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new PageNotFoundException();
            }
            relative = relative.resolve(part);
            sawSegment = true;
        }
        if (!sawSegment || relative.isAbsolute()) {
            throw new PageNotFoundException();
        }
        return relative.normalize();
    }

    public record PageContent(byte[] content, String contentType) {
        public String text() {
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    public record ImageContent(Resource resource, String contentType) {
    }

    public static class PageNotFoundException extends RuntimeException {
    }

    public static class PageReadException extends RuntimeException {
        public PageReadException(Throwable cause) {
            super(cause);
        }
    }
}
