package org.apiprivaterouter.javabackend.usercenter.repository;

import org.apiprivaterouter.javabackend.admin.subscription.model.AdminSubscriptionResponse;
import org.apiprivaterouter.javabackend.admin.subscription.service.AdminSubscriptionService;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class UserSubscriptionRepository {

    private final AdminSubscriptionService adminSubscriptionService;

    public UserSubscriptionRepository(AdminSubscriptionService adminSubscriptionService) {
        this.adminSubscriptionService = adminSubscriptionService;
    }

    public List<AdminSubscriptionResponse> listByUser(long userId) {
        return listAllByUser(userId, null);
    }

    public List<AdminSubscriptionResponse> listActiveByUser(long userId) {
        return listAllByUser(userId, "active");
    }

    public AdminSubscriptionResponse findByIdForUser(long subscriptionId, long userId) {
        return listByUser(userId).stream()
                .filter(subscription -> subscription.id() == subscriptionId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("subscription not found"));
    }

    private List<AdminSubscriptionResponse> listAllByUser(long userId, String status) {
        List<AdminSubscriptionResponse> items = new ArrayList<>();
        int page = 1;
        int totalPages;
        do {
            PageResponse<AdminSubscriptionResponse> response = adminSubscriptionService.listSubscriptions(
                    page,
                    200,
                    userId,
                    null,
                    status,
                    null,
                    "created_at",
                    "desc"
            );
            items.addAll(response.items());
            totalPages = Math.max(response.pages(), 1);
            page++;
        } while (page <= totalPages);
        return items;
    }
}
