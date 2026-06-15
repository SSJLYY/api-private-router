package org.apiprivaterouter.javabackend.usercenter.service;

import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.PasswordHasher;
import org.apiprivaterouter.javabackend.usercenter.model.AffiliateDetailResponse;
import org.apiprivaterouter.javabackend.usercenter.model.ChangePasswordRequest;
import org.apiprivaterouter.javabackend.usercenter.model.UpdateProfileRequest;
import org.apiprivaterouter.javabackend.usercenter.model.UserProfileResponse;
import org.apiprivaterouter.javabackend.usercenter.repository.UserCenterRepository;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class UserCenterService {

    private final UserCenterRepository userCenterRepository;
    private final PasswordHasher passwordHasher;

    public UserCenterService(UserCenterRepository userCenterRepository, PasswordHasher passwordHasher) {
        this.userCenterRepository = userCenterRepository;
        this.passwordHasher = passwordHasher;
    }

    public UserProfileResponse getProfile(CurrentUser currentUser) {
        return userCenterRepository.findProfileById(currentUser.userId())
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    public UserProfileResponse updateProfile(CurrentUser currentUser, UpdateProfileRequest request) {
        userCenterRepository.updateProfile(currentUser.userId(), request);
        return getProfile(currentUser);
    }

    public Map<String, String> changePassword(CurrentUser currentUser, ChangePasswordRequest request) {
        if (request.old_password().equals(request.new_password())) {
            throw new IllegalArgumentException("new_password must be different from old_password");
        }
        String currentHash = userCenterRepository.getPasswordHash(currentUser.userId());
        if (currentHash == null) {
            throw new IllegalArgumentException("No password set for this account. Use OAuth or set a password first.");
        }
        if (!BCrypt.checkpw(request.old_password(), currentHash)) {
            throw new IllegalArgumentException("current password is incorrect");
        }
        userCenterRepository.updatePassword(currentUser.userId(), passwordHasher.hash(request.new_password()));
        return Map.of("message", "Password changed successfully");
    }

    public AffiliateDetailResponse getAffiliateDetail(CurrentUser currentUser) {
        return userCenterRepository.findAffiliateDetail(currentUser.userId())
                .orElse(new AffiliateDetailResponse("", 0.0, 0.0, 0));
    }

    @Transactional
    public Map<String, Object> transferAffiliateQuota(CurrentUser currentUser) {
        double transferred = userCenterRepository.transferAffiliateQuota(currentUser.userId());
        UserProfileResponse profile = getProfile(currentUser);
        return Map.of(
                "transferred_quota", transferred,
                "balance", profile.balance()
        );
    }
}
