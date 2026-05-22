package org.apiprivaterouter.javabackend.admin.tlsfingerprint.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateTLSFingerprintProfileRequest(
        @NotBlank String name,
        String description,
        Boolean enable_grease,
        List<Integer> cipher_suites,
        List<Integer> curves,
        List<Integer> point_formats,
        List<Integer> signature_algorithms,
        List<String> alpn_protocols,
        List<Integer> supported_versions,
        List<Integer> key_share_groups,
        List<Integer> psk_modes,
        List<Integer> extensions
) {
}
