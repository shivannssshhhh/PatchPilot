package com.patchpilot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.util.Map;

@RestController
@CrossOrigin(origins = "${patchpilot.dashboard-origin:http://localhost:5173}")
public class HealthController {
    @GetMapping("/api/health")
    public Map<String, String> health() { return Map.of("status", "healthy", "service", "patchpilot-backend"); }
}
