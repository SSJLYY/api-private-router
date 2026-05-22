package org.apiprivaterouter.javabackend.admin.affiliate.model;

import java.time.OffsetDateTime;

public record AffiliateRecordFilter(
        String search,
        int page,
        int page_size,
        OffsetDateTime start_at,
        OffsetDateTime end_at,
        String sort_by,
        boolean sort_desc
) {
}
