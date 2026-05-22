package org.apiprivaterouter.javabackend.admin.userattribute.model;

import java.util.Map;

public record BatchUserAttributesResponse(
        Map<Long, Map<Long, String>> attributes
) {
}
