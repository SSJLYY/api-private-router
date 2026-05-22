package org.apiprivaterouter.javabackend.announcement.service;

import org.apiprivaterouter.javabackend.announcement.model.UserAnnouncementResponse;
import org.apiprivaterouter.javabackend.announcement.repository.AnnouncementRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AnnouncementService {

    private final AnnouncementRepository repository;

    public AnnouncementService(AnnouncementRepository repository) {
        this.repository = repository;
    }

    public List<UserAnnouncementResponse> listForUser(long userId, boolean unreadOnly) {
        return repository.listForUser(userId, unreadOnly);
    }

    public Map<String, String> markRead(long userId, long announcementId) {
        if (!repository.canUserAccessAnnouncement(userId, announcementId)) {
            throw new IllegalArgumentException("announcement not found");
        }
        repository.markRead(userId, announcementId);
        return Map.of("message", "ok");
    }
}
