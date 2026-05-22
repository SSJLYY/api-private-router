package org.apiprivaterouter.javabackend.admin.settings.model;

import java.util.List;

public record WebSearchTestResult(
        String provider,
        List<WebSearchResultItem> results,
        String query
) {

    public record WebSearchResultItem(
            String url,
            String title,
            String snippet,
            String page_age
    ) {
    }
}
