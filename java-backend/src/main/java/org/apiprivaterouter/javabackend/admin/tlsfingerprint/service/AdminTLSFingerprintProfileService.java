package org.apiprivaterouter.javabackend.admin.tlsfingerprint.service;

import org.apiprivaterouter.javabackend.admin.tlsfingerprint.model.CreateTLSFingerprintProfileRequest;
import org.apiprivaterouter.javabackend.admin.tlsfingerprint.model.TLSFingerprintProfileResponse;
import org.apiprivaterouter.javabackend.admin.tlsfingerprint.model.UpdateTLSFingerprintProfileRequest;
import org.apiprivaterouter.javabackend.admin.tlsfingerprint.repository.AdminTLSFingerprintProfileRepository;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminTLSFingerprintProfileService {

    private final AdminTLSFingerprintProfileRepository repository;

    public AdminTLSFingerprintProfileService(AdminTLSFingerprintProfileRepository repository) {
        this.repository = repository;
    }

    public List<TLSFingerprintProfileResponse> list() {
        return repository.list();
    }

    public TLSFingerprintProfileResponse getById(long id) {
        TLSFingerprintProfileResponse profile = repository.getById(id);
        if (profile == null) {
            throw new HttpStatusException(404, "Profile not found");
        }
        return profile;
    }

    public TLSFingerprintProfileResponse create(CreateTLSFingerprintProfileRequest request) {
        AdminTLSFingerprintProfileRepository.ProfileMutation mutation = buildCreateMutation(request);
        try {
            return repository.create(mutation);
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("name already exists");
        }
    }

    public TLSFingerprintProfileResponse update(long id, UpdateTLSFingerprintProfileRequest request) {
        TLSFingerprintProfileResponse existing = repository.getById(id);
        if (existing == null) {
            throw new HttpStatusException(404, "Profile not found");
        }
        AdminTLSFingerprintProfileRepository.ProfileMutation mutation = buildUpdateMutation(existing, request);
        try {
            TLSFingerprintProfileResponse updated = repository.update(id, mutation);
            if (updated == null) {
                throw new HttpStatusException(404, "Profile not found");
            }
            return updated;
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("name already exists");
        }
    }

    public void delete(long id) {
        TLSFingerprintProfileResponse existing = repository.getById(id);
        if (existing == null) {
            throw new HttpStatusException(404, "Profile not found");
        }
        repository.delete(id);
    }

    private AdminTLSFingerprintProfileRepository.ProfileMutation buildCreateMutation(CreateTLSFingerprintProfileRequest request) {
        String name = normalizeRequiredName(request.name());
        return new AdminTLSFingerprintProfileRepository.ProfileMutation(
                name,
                normalizeDescription(request.description()),
                Boolean.TRUE.equals(request.enable_grease()),
                normalizeIntegerList(request.cipher_suites()),
                normalizeIntegerList(request.curves()),
                normalizeIntegerList(request.point_formats()),
                normalizeIntegerList(request.signature_algorithms()),
                normalizeStringList(request.alpn_protocols()),
                normalizeIntegerList(request.supported_versions()),
                normalizeIntegerList(request.key_share_groups()),
                normalizeIntegerList(request.psk_modes()),
                normalizeIntegerList(request.extensions())
        );
    }

    private AdminTLSFingerprintProfileRepository.ProfileMutation buildUpdateMutation(
            TLSFingerprintProfileResponse existing,
            UpdateTLSFingerprintProfileRequest request
    ) {
        String name = request.name() == null ? existing.name() : normalizeRequiredName(request.name());
        String description = request.description() == null ? existing.description() : normalizeDescription(request.description());
        boolean enableGrease = request.enable_grease() == null ? existing.enable_grease() : request.enable_grease();
        return new AdminTLSFingerprintProfileRepository.ProfileMutation(
                name,
                description,
                enableGrease,
                request.cipher_suites() == null ? existing.cipher_suites() : normalizeIntegerList(request.cipher_suites()),
                request.curves() == null ? existing.curves() : normalizeIntegerList(request.curves()),
                request.point_formats() == null ? existing.point_formats() : normalizeIntegerList(request.point_formats()),
                request.signature_algorithms() == null ? existing.signature_algorithms() : normalizeIntegerList(request.signature_algorithms()),
                request.alpn_protocols() == null ? existing.alpn_protocols() : normalizeStringList(request.alpn_protocols()),
                request.supported_versions() == null ? existing.supported_versions() : normalizeIntegerList(request.supported_versions()),
                request.key_share_groups() == null ? existing.key_share_groups() : normalizeIntegerList(request.key_share_groups()),
                request.psk_modes() == null ? existing.psk_modes() : normalizeIntegerList(request.psk_modes()),
                request.extensions() == null ? existing.extensions() : normalizeIntegerList(request.extensions())
        );
    }

    private String normalizeRequiredName(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        return value.trim();
    }

    private String normalizeDescription(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private List<Integer> normalizeIntegerList(List<Integer> values) {
        return values == null ? List.of() : values;
    }

    private List<String> normalizeStringList(List<String> values) {
        return values == null ? List.of() : values.stream().map(value -> value == null ? "" : value).toList();
    }
}
