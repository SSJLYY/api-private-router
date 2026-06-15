package org.apiprivaterouter.javabackend.admin.ops.websocket;

import org.apiprivaterouter.javabackend.admin.ops.repository.AdminOpsRepository;
import org.apiprivaterouter.javabackend.admin.settings.repository.AdminSettingsRepository;
import org.apiprivaterouter.javabackend.common.db.JsonHelper;
import org.apiprivaterouter.javabackend.common.security.AuthUserRepository;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.JwtService;
import org.apiprivaterouter.javabackend.common.security.JwtUserPrincipal;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class AdminOpsQpsWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    private static final Logger log = LoggerFactory.getLogger(AdminOpsQpsWebSocketHandler.class);
    public static final int CLOSE_REALTIME_DISABLED = 4001;

    private final AdminSettingsRepository settingsRepository;
    private final AdminOpsRepository opsRepository;
    private final AuthUserRepository authUserRepository;
    private final JwtService jwtService;
    private final JsonHelper jsonHelper;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public AdminOpsQpsWebSocketHandler(
            AdminSettingsRepository settingsRepository,
            AdminOpsRepository opsRepository,
            AuthUserRepository authUserRepository,
            JwtService jwtService,
            JsonHelper jsonHelper
    ) {
        this.settingsRepository = settingsRepository;
        this.opsRepository = opsRepository;
        this.authUserRepository = authUserRepository;
        this.jwtService = jwtService;
        this.jsonHelper = jsonHelper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!isRealtimeEnabled()) {
            session.close(new CloseStatus(CLOSE_REALTIME_DISABLED, "realtime_disabled"));
            return;
        }
        CurrentUser currentUser = authenticate(session);
        if (currentUser == null || !"admin".equalsIgnoreCase(currentUser.role())) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("unauthorized"));
            return;
        }
        session.getAttributes().put("api-private-router.currentUser", currentUser);
        sendUpdate(session);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (session.isOpen()) {
                    sendUpdate(session);
                }
            } catch (Exception ex) {
                log.warn("QPS WebSocket sendUpdate failed for session {}: {}", session.getId(), ex.getMessage());
            }
        }, 2, 2, TimeUnit.SECONDS);
        session.getAttributes().put("api-private-router.qpsFuture", future);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object future = session.getAttributes().remove("api-private-router.qpsFuture");
        if (future instanceof ScheduledFuture<?> scheduledFuture) {
            scheduledFuture.cancel(true);
        }
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of("api-private-router-admin");
    }

    private void sendUpdate(WebSocketSession session) throws IOException {
        Instant end = Instant.now();
        Instant start = end.minusSeconds(60);
        Map<String, Object> stats = opsRepository.getWindowStats(start, end, null, null);
        long requestCount = longValue(stats.get("request_count_total"));
        long tokenConsumed = longValue(stats.get("token_consumed"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("qps", roundTo1DP(requestCount / 60.0d));
        data.put("tps", roundTo1DP(tokenConsumed / 60.0d));
        data.put("request_count", requestCount);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "qps_update");
        payload.put("timestamp", Instant.now().toString());
        payload.put("data", data);
        session.sendMessage(new TextMessage(jsonHelper.writeJson(payload)));
    }

    private boolean isRealtimeEnabled() {
        String opsEnabledRaw = settingsRepository.getSettingValue("ops_monitoring_enabled");
        String realtimeEnabledRaw = settingsRepository.getSettingValue("ops_realtime_monitoring_enabled");
        return isTrue(opsEnabledRaw) && isTrue(realtimeEnabledRaw);
    }

    private CurrentUser authenticate(WebSocketSession session) {
        String protocolHeader = session.getHandshakeHeaders().getFirst("Sec-WebSocket-Protocol");
        if (protocolHeader == null || protocolHeader.isBlank()) {
            return null;
        }
        for (String raw : protocolHeader.split(",")) {
            String tokenPart = raw.trim();
            if (!tokenPart.startsWith("jwt.")) {
                continue;
            }
            String token = tokenPart.substring(4);
            try {
                JwtUserPrincipal principal = jwtService.parseAccessToken(token);
                Optional<CurrentUser> currentUser = authUserRepository.findActiveUserById(principal.userId());
                if (currentUser.isPresent()) {
                    return currentUser.get();
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean isTrue(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String normalized = raw.trim();
        return !"false".equalsIgnoreCase(normalized)
                && !"0".equals(normalized)
                && !"off".equalsIgnoreCase(normalized)
                && !"disabled".equalsIgnoreCase(normalized);
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String raw && !raw.isBlank()) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private double roundTo1DP(double value) {
        return Math.round(value * 10.0d) / 10.0d;
    }
}
