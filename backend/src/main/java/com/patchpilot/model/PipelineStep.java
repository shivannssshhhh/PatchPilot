package com.patchpilot.model;

import java.time.Instant;
import java.util.Map;

public record PipelineStep(long id, String incidentId, String name, String status,
                           Map<String, Object> details, Instant createdAt) {
}
