package org.apiprivaterouter.javabackend.admin.group.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record CreateAdminGroupRequest(
        @NotBlank @Size(max = 100) String name,
        String description,
        String platform,
        @DecimalMin(value = "0.0001", inclusive = true) Double rate_multiplier,
        Boolean is_exclusive,
        String subscription_type,
        Double daily_limit_usd,
        Double weekly_limit_usd,
        Double monthly_limit_usd,
        Boolean allow_image_generation,
        Boolean image_rate_independent,
        Double image_rate_multiplier,
        Double image_price_1k,
        Double image_price_2k,
        Double image_price_4k,
        Boolean claude_code_only,
        Long fallback_group_id,
        Long fallback_group_id_on_invalid_request,
        Map<String, List<Long>> model_routing,
        Boolean model_routing_enabled,
        Boolean mcp_xml_inject,
        List<String> supported_model_scopes,
        GroupModelsListConfig models_list_config,
        Boolean allow_messages_dispatch,
        Boolean require_oauth_only,
        Boolean require_privacy_set,
        String default_mapped_model,
        AdminGroupResponse.MessagesDispatchModelConfig messages_dispatch_model_config,
        Integer rpm_limit,
        List<@Positive Long> copy_accounts_from_group_ids
) {
}
