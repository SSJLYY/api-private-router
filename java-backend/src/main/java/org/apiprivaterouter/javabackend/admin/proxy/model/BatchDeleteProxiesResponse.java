package org.apiprivaterouter.javabackend.admin.proxy.model;

import java.util.List;

public record BatchDeleteProxiesResponse(
        List<Long> deleted_ids,
        List<BatchDeleteSkippedResponse> skipped
) {
}
