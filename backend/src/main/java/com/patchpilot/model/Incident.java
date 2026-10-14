package com.patchpilot.model;

import java.time.Instant;
import java.util.Map;

public record Incident(String id, String status, Map<String, Object> payload, Instant createdAt,
                       Instant updatedAt, String declineReport) {
}
