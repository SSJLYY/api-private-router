package org.apiprivaterouter.javabackend.usercenter.model;

import org.apiprivaterouter.javabackend.admin.subscription.model.SubscriptionProgressResponse;

public record UserSubscriptionProgressItemResponse(
        UserSubscriptionResponse subscription,
        SubscriptionProgressResponse progress
) {
}
