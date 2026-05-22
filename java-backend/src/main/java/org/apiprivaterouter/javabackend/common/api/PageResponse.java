package org.apiprivaterouter.javabackend.common.api;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        long total,
        int page,
        int page_size,
        int pages
) {
    public PageResponse(List<T> items, long total, int page, int pageSize) {
        this(items, total, page, pageSize, calculatePages(total, pageSize));
    }

    private static int calculatePages(long total, int pageSize) {
        if (pageSize <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) total / pageSize);
    }
}
