package org.apiprivaterouter.javabackend.admin.affiliate.model;

import java.time.OffsetDateTime;

public record AffiliateTransferRecord(
        long ledger_id,
        long user_id,
        String user_email,
        String username,
        double amount,
        Double balance_after,
        Double available_quota_after,
        Double frozen_quota_after,
        Double history_quota_after,
        boolean snapshot_available,
        OffsetDateTime created_at
) {
}
