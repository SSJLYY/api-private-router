package org.apiprivaterouter.javabackend.pages.controller;

import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.pages.service.PagesService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/pages")
public class PagesController {

    private final PagesService pagesService;

    public PagesController(PagesService pagesService) {
        this.pagesService = pagesService;
    }

    @GetMapping
    public ApiResponse<List<String>> list() {
        requireAdmin();
        return ApiResponse.success(pagesService.listSlugs());
    }

    @GetMapping("/{slug}")
    public ResponseEntity<String> getPage(@PathVariable String slug) {
        PagesService.PageContent content = pagesService.loadPage(slug, isCurrentAdmin());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .body(content.text());
    }

    @GetMapping("/{slug}/images/**")
    public ResponseEntity<Resource> getImage(HttpServletRequest request, @PathVariable String slug) {
        String rawPath = extractImagePath(request, slug);
        PagesService.ImageContent content = pagesService.loadImage(slug, rawPath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                .body(content.resource());
    }

    private void requireAdmin() {
        if (!isCurrentAdmin()) {
            throw new org.apiprivaterouter.javabackend.common.api.UnauthorizedException("Admin authentication required");
        }
    }

    private boolean isCurrentAdmin() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return false;
        }
        Object currentUser = attributes.getRequest().getAttribute("api-private-router.currentUser");
        if (currentUser instanceof CurrentUser user) {
            return "admin".equalsIgnoreCase(user.role());
        }
        return false;
    }

    private String extractImagePath(HttpServletRequest request, String slug) {
        String prefix = "/api/v1/pages/" + slug + "/images/";
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith(prefix)) {
            return "";
        }
        return uri.substring(prefix.length());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(PagesService.PageNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePageNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "page not found"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(PagesService.PageReadException.class)
    public ResponseEntity<Map<String, String>> handlePageRead() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "failed to read page"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidInput(IllegalArgumentException ex) {
        if ("page too large".equals(ex.getMessage())) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "page too large"));
        }
        if (ex.getMessage() != null && ex.getMessage().contains("invalid page slug")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid page slug"));
        }
        throw ex;
    }
}
