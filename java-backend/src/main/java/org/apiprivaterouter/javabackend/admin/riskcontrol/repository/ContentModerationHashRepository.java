package org.apiprivaterouter.javabackend.admin.riskcontrol.repository;

import org.apiprivaterouter.javabackend.admin.settings.repository.AdminSettingsRepository;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Repository
public class ContentModerationHashRepository {

    private static final String SETTINGS_KEY = "content_moderation_flagged_hashes";

    private final AdminSettingsRepository settingsRepository;
    private final JsonHelper jsonHelper;

    public ContentModerationHashRepository(AdminSettingsRepository settingsRepository, JsonHelper jsonHelper) {
        this.settingsRepository = settingsRepository;
        this.jsonHelper = jsonHelper;
    }

    public long count() {
        return readHashes().size();
    }

    public boolean has(String inputHash) {
        String normalized = normalizeInputHash(inputHash);
        return normalized != null && readHashes().contains(normalized);
    }

    public void record(String inputHash) {
        String normalized = normalizeInputHash(inputHash);
        if (normalized == null) {
            return;
        }
        Set<String> hashes = readHashes();
        if (hashes.add(normalized)) {
            persist(hashes);
        }
    }

    public boolean delete(String inputHash) {
        String normalized = normalizeInputHash(inputHash);
        if (normalized == null) {
            return false;
        }
        Set<String> hashes = readHashes();
        boolean deleted = hashes.remove(normalized);
        if (deleted) {
            persist(hashes);
        }
        return deleted;
    }

    public long clearAll() {
        long deleted = readHashes().size();
        settingsRepository.deleteSetting(SETTINGS_KEY);
        return deleted;
    }

    private Set<String> readHashes() {
        String raw = settingsRepository.getSettingValue(SETTINGS_KEY);
        List<String> values = jsonHelper.readStringList(raw);
        return new LinkedHashSet<>(values);
    }

    private void persist(Set<String> hashes) {
        settingsRepository.upsertSettingValue(SETTINGS_KEY, jsonHelper.writeJson(hashes.stream().toList()));
    }

    private String normalizeInputHash(String inputHash) {
        if (inputHash == null) {
            return null;
        }
        String normalized = inputHash.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() != 64) {
            return null;
        }
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            boolean digit = ch >= '0' && ch <= '9';
            boolean lowerHex = ch >= 'a' && ch <= 'f';
            if (!digit && !lowerHex) {
                return null;
            }
        }
        return normalized;
    }
}
