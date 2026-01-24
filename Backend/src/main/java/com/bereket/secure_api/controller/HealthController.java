package com.bereket.secure_api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/health")
    public String health() {
        return "API is running, your health is good";
    }

    @GetMapping("/protected")
    public String protectedEndpoint() {
        return "You are authenticated with JWT";
    }
}
