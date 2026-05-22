package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;

public record BatchTodayStatsRequest(
        List<Long> account_ids
) {
}
