package org.apiprivaterouter.javabackend.admin.compliance.service;

import org.apiprivaterouter.javabackend.admin.compliance.model.*;
import org.apiprivaterouter.javabackend.admin.compliance.repository.AdminComplianceRepository;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
public class AdminComplianceService {

    private static final String COMPLIANCE_VERSION = "v2026.06.10";
    private static final Map<String, String> ACK_PHRASES = Map.of(
            "zh", "我已阅读并同意合规要求",
            "en", "I have read and agree to the compliance requirements"
    );
    private static final Map<String, String> DOCUMENT_URLS = Map.of(
            "zh", "/legal/admin-compliance",
            "en", "/legal/admin-compliance"
    );

    private final AdminComplianceRepository repository;
    private final JsonHelper jsonHelper;
    private final CurrentUserContext currentUserContext;

    public AdminComplianceService(AdminComplianceRepository repository, JsonHelper jsonHelper, CurrentUserContext currentUserContext) {
        this.repository = repository;
        this.jsonHelper = jsonHelper;
        this.currentUserContext = currentUserContext;
    }

    public AdminComplianceStatusResponse getStatus() {
        long adminUserId = currentUserContext.requireAdmin().userId();
        Optional<Map<String, Object>> ackOpt = repository.getAcknowledgement(adminUserId);

        AdminComplianceAcknowledgement acknowledgement = null;
        boolean required = true;

        if (ackOpt.isPresent()) {
            Map<String, Object> ack = ackOpt.get();
            String ackedVersion = ack.get("version") instanceof String s ? s : "";
            if (COMPLIANCE_VERSION.equals(ackedVersion)) {
                required = false;
            }
            acknowledgement = new AdminComplianceAcknowledgement(
                    ackedVersion,
                    ack.get("admin_user_id") instanceof Number n ? n.longValue() : 0,
                    ack.get("ip_address") instanceof String s ? s : "",
                    ack.get("user_agent") instanceof String s ? s : "",
                    ack.get("accepted_at") instanceof String s ? s : ""
            );
        }

        return new AdminComplianceStatusResponse(
                required,
                COMPLIANCE_VERSION,
                DOCUMENT_URLS,
                ACK_PHRASES,
                acknowledgement
        );
    }

    public AdminComplianceStatusResponse accept(AdminComplianceAcceptRequest request, HttpServletRequest httpRequest) {
        long adminUserId = currentUserContext.requireAdmin().userId();
        String language = request.language() != null ? request.language().toLowerCase() : "en";
        String expectedPhrase = ACK_PHRASES.getOrDefault(language, ACK_PHRASES.get("en"));

        if (request.phrase() == null || !request.phrase().trim().equals(expectedPhrase)) {
            throw new HttpStatusException(400, "ADMIN_COMPLIANCE_INVALID_PHRASE");
        }

        String ipAddress = httpRequest.getRemoteAddr();
        String userAgent = httpRequest.getHeader("User-Agent");

        repository.saveAcknowledgement(adminUserId, COMPLIANCE_VERSION, ipAddress, userAgent);
        return getStatus();
    }

    public boolean isComplianceRequired(long adminUserId) {
        Optional<Map<String, Object>> ackOpt = repository.getAcknowledgement(adminUserId);
        if (ackOpt.isEmpty()) {
            return true;
        }
        Map<String, Object> ack = ackOpt.get();
        String ackedVersion = ack.get("version") instanceof String s ? s : "";
        return !COMPLIANCE_VERSION.equals(ackedVersion);
    }
}
