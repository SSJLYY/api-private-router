package org.apiprivaterouter.javabackend.publicsettings.model;

public record CustomMenuItem(
        String id,
        String label,
        String icon_svg,
        String url,
        String page_slug,
        String visibility,
        int sort_order
) {
}
