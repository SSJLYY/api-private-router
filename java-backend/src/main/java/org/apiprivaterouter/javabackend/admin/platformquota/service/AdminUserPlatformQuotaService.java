package org.apiprivaterouter.javabackend.admin.platformquota.service;

import org.apiprivaterouter.javabackend.admin.platformquota.model.*;
import org.apiprivaterouter.javabackend.admin.platformquota.repository.UserPlatformQuotaRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminUserPlatformQuotaService {

    private final UserPlatformQuotaRepository quotaRepository;

    public AdminUserPlatformQuotaService(UserPlatformQuotaRepository quotaRepository) {
        this.quotaRepository = quotaRepository;
    }

    public UserPlatformQuotaListResponse listQuotas(long userId) {
        List<UserPlatformQuotaResponse> quotas = quotaRepository.listByUserId(userId);
        return new UserPlatformQuotaListResponse(quotas);
    }

    public UserPlatformQuotaListResponse replaceQuotas(long userId, ReplaceUserPlatformQuotaRequest request) {
        quotaRepository.upsertForUser(userId, request.quotas());
        List<UserPlatformQuotaResponse> quotas = quotaRepository.listByUserId(userId);
        return new UserPlatformQuotaListResponse(quotas);
    }

    public ResetPlatformQuotaResponse resetQuota(long userId, ResetPlatformQuotaRequest request) {
        return quotaRepository.resetWindow(userId, request.platform(), request.window());
    }
}
