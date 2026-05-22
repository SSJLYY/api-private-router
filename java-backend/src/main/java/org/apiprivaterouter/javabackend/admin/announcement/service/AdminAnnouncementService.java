package org.apiprivaterouter.javabackend.admin.announcement.service;

import org.apiprivaterouter.javabackend.admin.announcement.model.AnnouncementResponse;
import org.apiprivaterouter.javabackend.admin.announcement.model.CreateAnnouncementRequest;
import org.apiprivaterouter.javabackend.admin.announcement.model.UpdateAnnouncementRequest;
import org.apiprivaterouter.javabackend.admin.announcement.model.AnnouncementReadStatusResponse;
import org.apiprivaterouter.javabackend.admin.announcement.repository.AdminAnnouncementRepository;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminAnnouncementService {

    private static final List<String> ALLOWED_STATUSES = List.of("draft", "active", "archived");
    private static final List<String> ALLOWED_NOTIFY_MODES = List.of("silent", "popup");
    private static final List<String> ALLOWED_CONDITION_TYPES = List.of("subscription", "balance");
    private static final List<String> ALLOWED_OPERATORS = List.of("in", "gt", "gte", "lt", "lte", "eq");

    private final AdminAnnouncementRepository repository;
    private final JsonHelper jsonHelper;

    public AdminAnnouncementService(AdminAnnouncementRepository repository, JsonHelper jsonHelper) {
        this.repository = repository;
        this.jsonHelper = jsonHelper;
    }

    public PageResponse<AnnouncementResponse> listAnnouncements(int page, int pageSize, String status, String search, String sortBy, String sortOrder) {
        return repository.listAnnouncements(page, pageSize, normalizeStatus(status, true), trimSearch(search), sortBy, sortOrder);
    }

    public AnnouncementResponse getAnnouncement(long id) {
        return repository.getAnnouncement(id).orElseThrow(() -> new IllegalArgumentException("announcement not found"));
    }

    public AnnouncementResponse createAnnouncement(CreateAnnouncementRequest request, long actorId) {
        String title = requireText(request.title(), "title");
        String content = requireText(request.content(), "content");
        String status = normalizeStatus(request.status(), false);
        String notifyMode = normalizeNotifyMode(request.notify_mode(), false);
        Map<String, Object> targeting = normalizeTargeting(request.targeting());
        Instant startsAt = toInstant(request.starts_at(), false);
        Instant endsAt = toInstant(request.ends_at(), false);
        validateWindow(startsAt, endsAt);
        return repository.createAnnouncement(
                title,
                content,
                status,
                notifyMode,
                jsonHelper.writeJson(targeting),
                startsAt,
                endsAt,
                actorId
        );
    }

    public AnnouncementResponse updateAnnouncement(long id, UpdateAnnouncementRequest request, long actorId) {
        repository.getAnnouncement(id).orElseThrow(() -> new IllegalArgumentException("announcement not found"));
        String title = request.title() == null ? null : requireText(request.title(), "title");
        String content = request.content() == null ? null : requireText(request.content(), "content");
        String status = normalizeStatus(request.status(), true);
        String notifyMode = normalizeNotifyMode(request.notify_mode(), true);
        boolean targetingTouched = request.targeting() != null;
        Map<String, Object> targeting = targetingTouched ? normalizeTargeting(request.targeting()) : Map.of();
        boolean startsAtTouched = request.starts_at() != null;
        boolean endsAtTouched = request.ends_at() != null;
        Instant startsAt = toInstant(request.starts_at(), true);
        Instant endsAt = toInstant(request.ends_at(), true);
        validateWindow(startsAt, endsAt);
        return repository.updateAnnouncement(
                id,
                title,
                content,
                status,
                notifyMode,
                targetingTouched ? jsonHelper.writeJson(targeting) : "{}",
                targetingTouched,
                startsAt,
                startsAtTouched,
                endsAt,
                endsAtTouched,
                actorId
        );
    }

    public Map<String, String> deleteAnnouncement(long id) {
        repository.getAnnouncement(id).orElseThrow(() -> new IllegalArgumentException("announcement not found"));
        repository.deleteAnnouncement(id);
        return Map.of("message", "Announcement deleted successfully");
    }

    public PageResponse<AnnouncementReadStatusResponse> listReadStatus(long id, int page, int pageSize, String search, String sortBy, String sortOrder) {
        repository.getAnnouncement(id).orElseThrow(() -> new IllegalArgumentException("announcement not found"));
        return repository.listReadStatus(id, page, pageSize, trimSearch(search), sortBy, sortOrder);
    }

    private String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String trimmed = value.trim();
        if ("title".equals(field) && trimmed.length() > 200) {
            throw new IllegalArgumentException("title must be <= 200 chars");
        }
        return trimmed;
    }

    private String normalizeStatus(String value, boolean patch) {
        if (value == null || value.trim().isEmpty()) {
            return patch ? null : "draft";
        }
        String normalized = value.trim().toLowerCase();
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status must be draft, active, or archived");
        }
        return normalized;
    }

    private String normalizeNotifyMode(String value, boolean patch) {
        if (value == null || value.trim().isEmpty()) {
            return patch ? null : "silent";
        }
        String normalized = value.trim().toLowerCase();
        if (!ALLOWED_NOTIFY_MODES.contains(normalized)) {
            throw new IllegalArgumentException("notify_mode must be silent or popup");
        }
        return normalized;
    }

    private Map<String, Object> normalizeTargeting(Object rawTargeting) {
        if (rawTargeting == null) {
            return Map.of("any_of", List.of());
        }
        Map<String, Object> targeting = jsonHelper.readObjectMap(jsonHelper.writeJson(rawTargeting));
        Object anyOfRaw = targeting.get("any_of");
        if (anyOfRaw == null) {
            return Map.of("any_of", List.of());
        }
        List<Map<String, Object>> groups = jsonHelper.readObjectList(jsonHelper.writeJson(anyOfRaw));
        if (groups.size() > 50) {
            throw new IllegalArgumentException("invalid announcement targeting rules");
        }
        for (Map<String, Object> group : groups) {
            Object allOfRaw = group.get("all_of");
            List<Map<String, Object>> conditions = jsonHelper.readObjectList(jsonHelper.writeJson(allOfRaw));
            if (conditions.isEmpty() || conditions.size() > 50) {
                throw new IllegalArgumentException("invalid announcement targeting rules");
            }
            for (Map<String, Object> condition : conditions) {
                String type = stringValue(condition.get("type"));
                String operator = stringValue(condition.get("operator"));
                if (!ALLOWED_CONDITION_TYPES.contains(type)) {
                    throw new IllegalArgumentException("invalid announcement targeting rules");
                }
                if (!ALLOWED_OPERATORS.contains(operator)) {
                    throw new IllegalArgumentException("invalid announcement targeting rules");
                }
                if ("subscription".equals(type)) {
                    List<?> groupIds = condition.get("group_ids") instanceof List<?> ids ? ids : List.of();
                    if (groupIds.isEmpty()) {
                        throw new IllegalArgumentException("invalid announcement targeting rules");
                    }
                    for (Object groupId : groupIds) {
                        Long value = toLong(groupId);
                        if (value == null || value <= 0) {
                            throw new IllegalArgumentException("invalid announcement targeting rules");
                        }
                    }
                }
            }
            group.put("all_of", conditions);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("any_of", groups);
        return normalized;
    }

    private Instant toInstant(Long epochSeconds, boolean patch) {
        if (epochSeconds == null) {
            return null;
        }
        if (patch && epochSeconds == 0L) {
            return null;
        }
        if (!patch && epochSeconds <= 0) {
            return null;
        }
        return Instant.ofEpochSecond(epochSeconds);
    }

    private void validateWindow(Instant startsAt, Instant endsAt) {
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("ends_at must be after starts_at");
        }
    }

    private String trimSearch(String search) {
        if (search == null) {
            return null;
        }
        String trimmed = search.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase();
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }
}
