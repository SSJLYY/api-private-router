package org.apiprivaterouter.javabackend.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping
public class CommonCompatibilityController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/api/event_logging/batch")
    @ResponseStatus(HttpStatus.OK)
    public void batchEventLogging() {
        // Compatibility no-op for Claude Code event logging.
    }
}
