package org.apiprivaterouter.javabackend.admin.service;

import org.apiprivaterouter.javabackend.admin.model.AdminBoundAuthIdentityResponse;
import org.apiprivaterouter.javabackend.admin.model.AdminUserBalanceHistoryResponse;
import org.apiprivaterouter.javabackend.admin.model.AdminUserRpmStatusResponse;
import org.apiprivaterouter.javabackend.admin.model.BindUserAuthIdentityRequest;
import org.apiprivaterouter.javabackend.admin.model.AdminUserResponse;
import org.apiprivaterouter.javabackend.admin.model.CreateAdminUserRequest;
import org.apiprivaterouter.javabackend.admin.model.UpdateAdminUserRequest;
import org.apiprivaterouter.javabackend.admin.model.AdminUserUsageResponse;
import org.apiprivaterouter.javabackend.admin.repository.AdminUserRepository;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.PasswordHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class AdminUserService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordHasher passwordHasher;

    public AdminUserService(AdminUserRepository adminUserRepository, PasswordHasher passwordHasher) {
        this.adminUserRepository = adminUserRepository;
        this.passwordHasher = passwordHasher;
    }

    public PageResponse<AdminUserResponse> listUsers(int page, int pageSize, String status, String role, String search, String groupName, String sortBy, String sortOrder) {
        return adminUserRepository.listUsers(page, pageSize, status, role, search, groupName, sortBy, sortOrder);
    }

    public AdminUserResponse getUser(long id) {
        return adminUserRepository.getUser(id).orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    public AdminUserResponse createUser(CreateAdminUserRequest request) {
        long id = adminUserRepository.createUser(request, passwordHasher.hash(request.password()));
        return getUser(id);
    }

    public AdminUserResponse updateUser(long id, UpdateAdminUserRequest request) {
        String passwordHash = request.password() == null || request.password().isBlank() ? null : passwordHasher.hash(request.password());
        adminUserRepository.updateUser(id, request, passwordHash);
        return getUser(id);
    }

    public Map<String, String> deleteUser(long id) {
        adminUserRepository.softDeleteUser(id);
        return Map.of("message", "User deleted successfully");
    }

    @Transactional
    public AdminUserResponse updateBalance(long id, double balance, String operation, String notes) {
        double delta = switch (operation) {
            case "add" -> balance;
            case "subtract" -> -balance;
            case "set" -> Double.NaN;
            default -> throw new IllegalArgumentException("invalid operation: " + operation);
        };
        if (Double.isNaN(delta)) {
            adminUserRepository.updateUserBalance(id, balance, notes);
        } else {
            adminUserRepository.adjustUserBalance(id, delta, notes);
        }
        return getUser(id);
    }

    public PageResponse<Map<String, Object>> getUserApiKeys(long id, int page, int pageSize) {
        return adminUserRepository.getUserApiKeys(id, page, pageSize);
    }

    public AdminUserUsageResponse getUserUsage(long id, String period) {
        return adminUserRepository.getUserUsage(id, period);
    }

    public AdminUserBalanceHistoryResponse getUserBalanceHistory(long id, int page, int pageSize, String type) {
        return adminUserRepository.getUserBalanceHistory(id, page, pageSize, type);
    }

    public AdminUserRpmStatusResponse getUserRpmStatus(long id) {
        return adminUserRepository.getUserRpmStatus(id);
    }

    public int batchUpdateConcurrency(List<Long> userIds, boolean all, int concurrency, String mode) {
        String normalizedMode = mode == null ? "" : mode.trim().toLowerCase();
        if (!"set".equals(normalizedMode) && !"add".equals(normalizedMode)) {
            throw new IllegalArgumentException("mode must be 'set' or 'add'");
        }

        List<Long> targetUserIds;
        if (all) {
            targetUserIds = adminUserRepository.listAllActiveUserIds();
        } else {
            if (userIds == null || userIds.isEmpty()) {
                throw new IllegalArgumentException("user_ids is required unless all=true");
            }
            if (userIds.size() > 500) {
                throw new IllegalArgumentException("user_ids cannot exceed 500");
            }
            targetUserIds = new ArrayList<>(new LinkedHashSet<>(userIds.stream()
                    .filter(id -> id != null && id > 0)
                    .toList()));
        }

        if (targetUserIds.isEmpty()) {
            return 0;
        }
        return "set".equals(normalizedMode)
                ? adminUserRepository.batchSetConcurrency(targetUserIds, concurrency)
                : adminUserRepository.batchAddConcurrency(targetUserIds, concurrency);
    }

    public AdminBoundAuthIdentityResponse bindUserAuthIdentity(long userId, BindUserAuthIdentityRequest request) {
        return adminUserRepository.bindUserAuthIdentity(userId, request);
    }

    public int replaceUserGroup(long userId, long oldGroupId, long newGroupId) {
        if (oldGroupId <= 0 || newGroupId <= 0) {
            throw new IllegalArgumentException("old_group_id and new_group_id must be > 0");
        }
        if (oldGroupId == newGroupId) {
            throw new IllegalArgumentException("old_group_id and new_group_id must be different");
        }
        return adminUserRepository.replaceUserGroup(userId, oldGroupId, newGroupId);
    }
}
