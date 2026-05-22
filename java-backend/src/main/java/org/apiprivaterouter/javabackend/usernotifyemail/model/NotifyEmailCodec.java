package org.apiprivaterouter.javabackend.usernotifyemail.model;

import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.usercenter.model.NotifyEmailEntry;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class NotifyEmailCodec {

    private NotifyEmailCodec() {
    }

    public static List<NotifyEmailEntry> parse(JsonHelper jsonHelper, String raw) {
        List<Map<String, Object>> objectEntries = jsonHelper.readObjectList(raw);
        if (!objectEntries.isEmpty()) {
            return objectEntries.stream()
                    .map(NotifyEmailCodec::fromObjectEntry)
                    .filter(Objects::nonNull)
                    .toList();
        }

        List<String> stringEntries = jsonHelper.readStringList(raw);
        if (!stringEntries.isEmpty()) {
            return stringEntries.stream()
                    .map(NotifyEmailCodec::trimToNull)
                    .filter(Objects::nonNull)
                    .map(email -> new NotifyEmailEntry(email, false, false))
                    .toList();
        }

        return List.of();
    }

    private static NotifyEmailEntry fromObjectEntry(Map<String, Object> entry) {
        if (entry == null || entry.isEmpty()) {
            return null;
        }
        String email = trimToNull(entry.get("email"));
        if (email == null) {
            return null;
        }
        boolean disabled = Boolean.parseBoolean(String.valueOf(entry.getOrDefault("disabled", false)));
        boolean verified = Boolean.parseBoolean(String.valueOf(entry.getOrDefault("verified", false)));
        return new NotifyEmailEntry(email, disabled, verified);
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
