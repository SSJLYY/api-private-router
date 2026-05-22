package org.apiprivaterouter.javabackend.admin.model;

import java.util.List;

public record AdminUserBalanceHistoryResponse(
        List<AdminUserBalanceHistoryItemResponse> items,
        long total,
        int page,
        int page_size,
        int pages,
        double total_recharged
) {
}
