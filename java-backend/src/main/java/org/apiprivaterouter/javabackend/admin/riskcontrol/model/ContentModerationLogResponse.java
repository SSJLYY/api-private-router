package org.apiprivaterouter.javabackend.admin.riskcontrol.model;

import java.util.Map;

public record ContentModerationLogResponse(
        long id,
        String request_id,
        Long user_id,
        String user_email,
        Long api_key_id,
        String api_key_name,
        Long group_id,
        String group_name,
        String endpoint,
        String provider,
        String model,
        String mode,
        String action,
        boolean flagged,
        String highest_category,
        double highest_score,
        Map<String, Double> category_scores,
        Map<String, Double> threshold_snapshot,
        String input_excerpt,
        Integer upstream_latency_ms,
        String error,
        int violation_count,
        boolean auto_banned,
        boolean email_sent,
        String user_status,
        Integer queue_delay_ms,
        String created_at
) {
}
