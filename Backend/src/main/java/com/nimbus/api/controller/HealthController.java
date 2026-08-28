package com.nimbus.api.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    /** Public liveness ping. Cheap, and says nothing about the internals. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "up",
                "service", "nimbus-core",
                "time", Instant.now()
        );
    }

    /** Proves the session cookie is doing its job end to end. */
    @GetMapping("/protected")
    public Map<String, Object> protectedEndpoint(Authentication authentication) {
        return Map.of(
                "status", "authenticated",
                "username", authentication.getName(),
                "time", Instant.now()
        );
    }
}
