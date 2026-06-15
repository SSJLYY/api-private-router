package org.apiprivaterouter.javabackend.setup;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/setup")
public class SetupController {

    private static final Set<String> ALLOWED_REMOTE_ADDRS = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    private void requireLocalhost(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!ALLOWED_REMOTE_ADDRS.contains(remoteAddr)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Setup endpoints are only accessible from localhost");
        }
    }

    @GetMapping("/status")
    public ApiResponse<SetupStatusResponse> status() {
        return ApiResponse.success(setupService.status());
    }

    @PostMapping("/test-db")
    public ApiResponse<Map<String, Object>> testDatabase(
            HttpServletRequest request,
            @RequestBody(required = false) SetupDatabaseRequest dbRequest) {
        requireLocalhost(request);
        return ApiResponse.success(setupService.testDatabase(dbRequest));
    }

    @PostMapping("/test-redis")
    public ApiResponse<Map<String, Object>> testRedis(
            HttpServletRequest request,
            @RequestBody(required = false) SetupRedisRequest redisRequest) {
        requireLocalhost(request);
        return ApiResponse.success(setupService.testRedis(redisRequest));
    }

    @PostMapping("/install")
    public ApiResponse<Map<String, Object>> install(
            HttpServletRequest request,
            @RequestBody(required = false) SetupInstallRequest installRequest) {
        requireLocalhost(request);
        return ApiResponse.success(setupService.install(installRequest));
    }
}
