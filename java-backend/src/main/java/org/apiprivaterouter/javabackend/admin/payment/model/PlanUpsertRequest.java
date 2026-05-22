package org.apiprivaterouter.javabackend.admin.payment.model;

public record PlanUpsertRequest(
        Long group_id,
        String name,
        String description,
        Double price,
        Double original_price,
        Integer validity_days,
        String validity_unit,
        String features,
        String product_name,
        Boolean for_sale,
        Integer sort_order
) {
}
