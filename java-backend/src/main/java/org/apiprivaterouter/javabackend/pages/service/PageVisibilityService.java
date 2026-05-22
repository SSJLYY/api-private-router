package org.apiprivaterouter.javabackend.pages.service;

import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.publicsettings.model.CustomMenuItem;
import org.apiprivaterouter.javabackend.publicsettings.repository.PublicSettingsRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PageVisibilityService {

    private static final String KEY_CUSTOM_MENU_ITEMS = "custom_menu_items";

    private final PublicSettingsRepository publicSettingsRepository;
    private final JsonHelper jsonHelper;

    public PageVisibilityService(PublicSettingsRepository publicSettingsRepository, JsonHelper jsonHelper) {
        this.publicSettingsRepository = publicSettingsRepository;
        this.jsonHelper = jsonHelper;
    }

    public VisibilityResult resolve(String slug) {
        if (slug == null || slug.isBlank()) {
            return VisibilityResult.notFound();
        }
        Map<String, String> settings = publicSettingsRepository.getValues(List.of(KEY_CUSTOM_MENU_ITEMS));
        List<CustomMenuItem> items = jsonHelper.readList(settings.get(KEY_CUSTOM_MENU_ITEMS), CustomMenuItem.class);
        for (CustomMenuItem item : items) {
            if (item == null) {
                continue;
            }
            String pageSlug = normalizeSlug(item.page_slug(), item.url());
            if (slug.equals(pageSlug)) {
                boolean adminOnly = "admin".equalsIgnoreCase(blankToEmpty(item.visibility()));
                return new VisibilityResult(true, adminOnly);
            }
        }
        return VisibilityResult.notFound();
    }

    private String normalizeSlug(String pageSlug, String url) {
        String direct = trimToNull(pageSlug);
        if (direct != null) {
            return direct;
        }
        String resolvedUrl = trimToNull(url);
        if (resolvedUrl != null && resolvedUrl.startsWith("md:")) {
            String candidate = trimToNull(resolvedUrl.substring(3));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public record VisibilityResult(boolean found, boolean adminOnly) {
        public static VisibilityResult notFound() {
            return new VisibilityResult(false, false);
        }
    }
}
