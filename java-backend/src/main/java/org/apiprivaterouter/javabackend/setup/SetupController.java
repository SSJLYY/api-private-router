package org.apiprivaterouter.javabackend.setup;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;

import java.util.Map;

@RestController
@RequestMapping("/setup")
public class SetupController {

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @GetMapping("/status")
    public ApiResponse<SetupStatusResponse> status() {
        return ApiResponse.success(setupService.status());
    }

    @PostMapping("/test-db")
    public ApiResponse<Map<String, Object>> testDatabase(@RequestBody(required = false) SetupDatabaseRequest request) {
        return ApiResponse.success(setupService.testDatabase(request));
    }

    @PostMapping("/test-redis")
    public ApiResponse<Map<String, Object>> testRedis(@RequestBody(required = false) SetupRedisRequest request) {
        return ApiResponse.success(setupService.testRedis(request));
    }

    @PostMapping("/install")
    public ApiResponse<Map<String, Object>> install(@RequestBody(required = false) SetupInstallRequest request) {
        return ApiResponse.success(setupService.install(request));
    }
}
