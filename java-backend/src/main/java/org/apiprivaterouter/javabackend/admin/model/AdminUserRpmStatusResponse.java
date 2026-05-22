package org.apiprivaterouter.javabackend.admin.model;

import java.util.List;

public record AdminUserRpmStatusResponse(
        long user_rpm_used,
        Integer user_rpm_limit,
        List<AdminUserGroupRpmStatusResponse> per_group
) {
    public record AdminUserGroupRpmStatusResponse(
            long group_id,
            String group_name,
            Integer limit,
            long used,
            String source
    ) {
    }
}
