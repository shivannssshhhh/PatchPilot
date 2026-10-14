package com.patchpilot.model;

import java.time.Instant;
import java.util.Map;

public record Artifact(long id, String incidentId, String type, Map<String, Object> content, Instant createdAt) {
}
