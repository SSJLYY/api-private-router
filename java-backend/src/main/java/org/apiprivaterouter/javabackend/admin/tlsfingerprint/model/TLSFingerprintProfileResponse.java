package org.apiprivaterouter.javabackend.admin.tlsfingerprint.model;

import java.util.List;

public record TLSFingerprintProfileResponse(
        long id,
        String name,
        String description,
        boolean enable_grease,
        List<Integer> cipher_suites,
        List<Integer> curves,
        List<Integer> point_formats,
        List<Integer> signature_algorithms,
        List<String> alpn_protocols,
        List<Integer> supported_versions,
        List<Integer> key_share_groups,
        List<Integer> psk_modes,
        List<Integer> extensions,
        String created_at,
        String updated_at
) {
}
