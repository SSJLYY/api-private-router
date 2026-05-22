package org.apiprivaterouter.javabackend.admin.account.model;

import java.util.List;
import java.util.Map;

public record AccountUsageInfoResponse(
        String source,
        String updated_at,
        AccountUsageProgressResponse five_hour,
        AccountUsageProgressResponse seven_day,
        AccountUsageProgressResponse seven_day_sonnet,
        AccountUsageProgressResponse gemini_shared_daily,
        AccountUsageProgressResponse gemini_pro_daily,
        AccountUsageProgressResponse gemini_flash_daily,
        AccountUsageProgressResponse gemini_shared_minute,
        AccountUsageProgressResponse gemini_pro_minute,
        AccountUsageProgressResponse gemini_flash_minute,
        Map<String, AntigravityModelQuotaResponse> antigravity_quota,
        List<AccountAiCreditResponse> ai_credits,
        Boolean is_forbidden,
        String forbidden_reason,
        String forbidden_type,
        String validation_url,
        Boolean needs_verify,
        Boolean is_banned,
        Boolean needs_reauth,
        String error_code,
        String error
) {
}
